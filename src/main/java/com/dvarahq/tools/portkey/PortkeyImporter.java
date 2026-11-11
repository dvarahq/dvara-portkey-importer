/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dvarahq.tools.portkey;

import com.dvarahq.tools.portkey.DvaraConfig.Fallback;
import com.dvarahq.tools.portkey.DvaraConfig.Policy;
import com.dvarahq.tools.portkey.DvaraConfig.Provider;
import com.dvarahq.tools.portkey.DvaraConfig.Route;
import com.dvarahq.tools.portkey.DvaraConfig.WeightedProvider;
import com.dvarahq.tools.portkey.DvaraConfig.Workspace;
import com.dvarahq.tools.portkey.ImportReport.Kind;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads Portkey gateway configs (the JSON sent in {@code x-portkey-config} or saved in Portkey's app) and builds
 * the equivalent open-gateway configuration, with a report of what carried over, what changed and what has no
 * equivalent.
 *
 * <p>Three rules hold throughout. A secret value is never copied: each key becomes the name of the environment
 * variable that holds it. A target that names a server of its own is never pointed at the provider's public
 * API. And nothing in the input is passed over in silence: a key this class does not map is named in the
 * report, and a check that blocked requests in Portkey and is not enforced here is listed under "You need to".
 */
final class PortkeyImporter {

    /** The workspace a key with no workspace of its own belongs to, in the open gateway. */
    static final String DEFAULT_WORKSPACE = "default";

    /** The most backups a route's chain may name. */
    static final int MAX_FALLBACKS = 5;

    /** The route model that matches every request. */
    static final String CATCH_ALL = "*";

    /** Portkey's retry codes when {@code retry.on_status_codes} is not set (src/globals.ts). */
    static final List<Integer> PORTKEY_RETRY_CODES = List.of(429, 500, 502, 503, 504);

    private static final String RESILIENCE = "dvara.llm-gateway.resilience.";

    /** The configuration and the report. */
    record Result(DvaraConfig config, ImportReport report) {}

    /** One config to import, and the name it goes by in the output. */
    record Source(String id, Map<String, Object> config) {}

    /** Input the importer cannot read at all, as opposed to parts of it that do not map. */
    static final class ImportException extends RuntimeException {
        ImportException(String message) {
            super(message);
        }
    }

    /** A target that maps onto a provider: where it is, the provider, the model it is sent (null: the caller's). */
    private record Leaf(String where, ProviderType type, String model, double weight) {}

    /** What a target or group serves: providers sharing one model, by weight, then backups in order. */
    private record Plan(List<Leaf> primary, List<Leaf> fallbacks) {
        String model() {
            return primary.get(0).model();
        }
    }

    /** What a target takes from the groups around it, as Portkey's tryTargetsRecursively merges it. */
    private record Inherited(Map<String, Object> overrideParams, Map<String, Object> retry, Object requestTimeout,
                             String customHost) {
        static final Inherited NONE = new Inherited(Map.of(), null, null, null);
    }

    /** Retry and timeout of one served target, gathered so they can be written per provider or for all. */
    private record Resilience(ProviderType type, String where, int retries, Long timeoutMs) {}

    /** A guardrail check that maps onto a model allowlist or denylist policy. */
    private record ModelCheck(String where, List<String> models, boolean deny) {}

    private record RouteDraft(String id, String model, String pinned, List<WeightedProvider> providers,
                              List<Fallback> fallbacks) {
        String sentModel() {
            return pinned != null ? pinned : model;
        }
    }

    private final DvaraConfig out = new DvaraConfig();
    private final ImportReport report = new ImportReport();
    private final boolean multi;

    private final Map<ProviderType, String> providerFingerprints = new LinkedHashMap<>();
    private final Map<ProviderType, List<String>> providerSources = new LinkedHashMap<>();
    private final Map<String, RouteDraft> routesByModel = new LinkedHashMap<>();
    private final Set<String> routeIds = new LinkedHashSet<>();
    private final Set<String> envNames = new TreeSet<>();
    private final Map<String, Object> workspaceMetadata = new LinkedHashMap<>();
    private final List<Resilience> resilience = new ArrayList<>();
    private final List<ModelCheck> modelChecks = new ArrayList<>();
    /** Vault references whose provider was guessed, so the report asks once for each. */
    private final Set<String> inferredRefs = new LinkedHashSet<>();
    private boolean anyMetadataCondition;

    /** The config being read, for the report's "where". */
    private String currentId;

    private PortkeyImporter(boolean multi) {
        this.multi = multi;
    }

    // ------------------------------------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------------------------------------

    /** Imports one file's text; {@code name} is the file's base name, which names the configs in it. */
    static Result importJson(String text, String name) {
        return importSources(sources(parse(text), name));
    }

    static Object parse(String text) {
        try {
            return Json.parse(text);
        } catch (Json.ParseException e) {
            throw new ImportException("not valid JSON: " + e.getMessage());
        }
    }

    /**
     * The configs in one parsed file: a config object, a JSON array of them, or the wrapper Portkey's admin API
     * returns ({@code {"data": {"config": {...}}}} or {@code {"config": ...}}).
     */
    static List<Source> sources(Object root, String name) {
        String base = idOf(name);
        if (root instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new ImportException("the JSON array is empty, so there is nothing to import");
            }
            List<Source> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                result.add(unwrap(list.get(i), base + "-" + (i + 1), "[" + i + "]"));
            }
            return result;
        }
        return List.of(unwrap(root, base, "the file"));
    }

    private static Source unwrap(Object value, String id, String what) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new ImportException(what + " is not a Portkey config: it is not a JSON object");
        }
        Map<String, Object> map = stringKeys(m);
        if (!looksLikeConfig(map) && map.get("data") instanceof Map<?, ?> data) {
            return unwrap(data, id, what);
        }
        if (!looksLikeConfig(map) && map.containsKey("config")) {
            Object inner = map.get("config");
            if (inner instanceof String s) {
                inner = parse(s);
            }
            for (String k : List.of("slug", "name")) {
                if (map.get(k) instanceof String n && !n.isBlank()) {
                    id = idOf(n);
                    break;
                }
            }
            return unwrap(inner, id, what);
        }
        if (!looksLikeConfig(map)) {
            throw new ImportException(what + " is not a Portkey config: it has none of strategy, targets, provider,"
                    + " virtual_key, retry, cache or guardrails");
        }
        return new Source(id, map);
    }

    private static boolean looksLikeConfig(Map<String, Object> map) {
        return map.keySet().stream().map(PortkeyImporter::snake).anyMatch(k -> Set.of("strategy", "targets",
                "provider", "virtual_key", "mode", "options", "passthrough", "retry", "cache", "request_timeout",
                "input_guardrails", "output_guardrails", "before_request_hooks", "after_request_hooks").contains(k));
    }

    static Result importSources(List<Source> sources) {
        PortkeyImporter importer = new PortkeyImporter(sources.size() > 1);
        importer.run(sources);
        return new Result(importer.out, importer.report);
    }

    // ------------------------------------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------------------------------------

    private void run(List<Source> sources) {
        Set<String> ids = new LinkedHashSet<>();
        for (Source s : sources) {
            String id = s.id();
            for (int n = 2; !ids.add(id); n++) {
                id = s.id() + "-" + n;
            }
            importConfig(id, s.config());
        }
        currentId = null;
        reportProviders();
        // The gateway takes the first route that matches, so the catch-all goes last.
        routesByModel.values().stream()
                .sorted(Comparator.comparing((RouteDraft d) -> d.model().equals(CATCH_ALL)))
                .forEach(d -> out.routes.add(new Route(d.id(), d.model(), d.pinned(), d.providers(), d.fallbacks())));
        writeResilience();
        buildWorkspaceAndPolicies();
        finalActions();
    }

    private void importConfig(String id, Map<String, Object> raw) {
        currentId = id;
        Map<String, Object> root = node(raw, "");
        int routesBefore = routesByModel.size();
        Plan main;
        Map<String, Object> strategy = map(root.get("strategy"));
        if (strategy != null && "conditional".equals(strategy.get("mode"))) {
            main = rootConditional(id, root, strategy);
        } else {
            main = compile(root, "", Inherited.NONE);
        }
        String w = where("");
        if (main == null) {
            if (routesByModel.size() == routesBefore) {
                report.add(Kind.NOT_MAPPED, w, "No target in this config could be served, so it has no route.");
            }
            return;
        }
        if (!multi) {
            addRoute(CATCH_ALL, main, w);
            report.add(Kind.MAPPED, w, "Route `" + routesByModel.get(CATCH_ALL).id() + "` serves every request, as"
                    + " this config did when it was attached to every call." + sends(main));
            return;
        }
        if (main.model() != null) {
            if (routesByModel.containsKey(id)) {
                report.add(Kind.NOT_MAPPED, w, "A route for model `" + id + "` already exists; this config is not"
                        + " served. Rename the file and run the importer again.");
                return;
            }
            addRoute(id, main, w);
            report.add(Kind.CHANGED, w, "Route `" + routesByModel.get(id).id() + "`. Portkey applied this config"
                    + " to requests that named it in x-portkey-config (or whose API key had it as default config)."
                    + " The gateway picks a route by the model a request names: callers send `model: \"" + id
                    + "\"`." + sends(main));
            return;
        }
        if (!routesByModel.containsKey(CATCH_ALL)) {
            addRoute(CATCH_ALL, main, w);
            report.add(Kind.CHANGED, w, "This config sends the caller's own model, so it cannot be picked by a model"
                    + " name. Its route `" + routesByModel.get(CATCH_ALL).id() + "` serves every request no other"
                    + " route matches.");
            return;
        }
        report.add(Kind.NOT_MAPPED, w, "Not served. It sends the caller's own model, so it can only be a route for"
                + " every model, and another config already is.");
        report.add(Kind.ACTION, w, "Add override_params.model to its first target, or import it on its own, to"
                + " give it a route.");
    }

    private static String sends(Plan plan) {
        return plan.model() == null ? " The provider is sent the caller's model."
                : " The provider is sent `" + plan.model() + "`.";
    }

    // ------------------------------------------------------------------------------------------------
    // Targets and groups
    // ------------------------------------------------------------------------------------------------

    /** What one target or group serves; null, with report lines, when nothing in it can be served. */
    private Plan compile(Map<String, Object> node, String path, Inherited parent) {
        String w = where(path);
        Inherited inh = inherit(parent, node, w);
        readNodeSettings(node, w, path.isEmpty());

        Map<String, Object> strategy = map(node.get("strategy"));
        if (strategy == null) {
            if (node.get("targets") instanceof List<?> l && !l.isEmpty()) {
                report.add(Kind.NOT_MAPPED, w, "`targets` without `strategy.mode`: Portkey calls this target itself"
                        + " and ignores them, so they are not read.");
            }
            return leaf(node, w, inh);
        }
        for (String k : List.of("provider", "virtual_key", "api_key", "passthrough")) {
            if (node.containsKey(k)) {
                report.add(Kind.NOT_MAPPED, w, "`" + k + "` on a target with a strategy: Portkey calls its targets,"
                        + " not this one, so it is not read.");
            }
        }
        String mode = String.valueOf(strategy.get("mode"));
        List<Map<String, Object>> targets = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        if (node.get("targets") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String p = (path.isEmpty() ? "" : path + ".") + "targets[" + i + "]";
                if (list.get(i) instanceof Map<?, ?> m) {
                    targets.add(node(stringKeys(m), p));
                    paths.add(p);
                } else {
                    report.add(Kind.NOT_MAPPED, where(p), "Not a JSON object; not read.");
                }
            }
        }
        if (targets.isEmpty()) {
            report.add(Kind.NOT_MAPPED, w, "Strategy `" + mode + "` with no targets; nothing to serve.");
            return null;
        }
        for (String k : strategy.keySet()) {
            if (!Set.of("mode", "on_status_codes", "conditions", "default").contains(k)) {
                report.add(Kind.NOT_MAPPED, w, "strategy." + k + " is not carried over.");
            }
        }
        return switch (mode) {
            case "single" -> single(w, targets, paths, inh);
            case "fallback" -> fallback(w, strategy, node, targets, paths, inh);
            case "loadbalance" -> loadbalance(w, targets, paths, inh);
            case "conditional" -> nestedConditional(w, strategy, targets, paths, inh);
            default -> {
                report.add(Kind.NOT_MAPPED, w, "strategy.mode `" + mode + "` is not one Portkey documents; not read.");
                yield null;
            }
        };
    }

    private Plan single(String w, List<Map<String, Object>> targets, List<String> paths, Inherited inh) {
        if (targets.size() > 1) {
            report.add(Kind.NOT_MAPPED, w, "Mode `single` calls only the first target, as in Portkey; the other "
                    + (targets.size() - 1) + " are not read.");
        }
        Plan p = compile(targets.get(0), paths.get(0), inh);
        if (p != null) {
            report.add(Kind.MAPPED, w, "Mode `single`: the first target serves every request.");
        }
        return p;
    }

    private Plan fallback(String w, Map<String, Object> strategy, Map<String, Object> node,
                          List<Map<String, Object>> targets, List<String> paths, Inherited inh) {
        List<Plan> plans = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Plan p = compile(targets.get(i), paths.get(i), inh);
            if (p != null) {
                plans.add(p);
            }
        }
        if (plans.isEmpty()) {
            return null;
        }
        Plan first = plans.get(0);
        List<Leaf> backups = new ArrayList<>(first.fallbacks());
        boolean balancedBackup = false;
        for (Plan p : plans.subList(1, plans.size())) {
            balancedBackup |= p.primary().size() > 1;
            backups.addAll(p.primary());
            backups.addAll(p.fallbacks());
        }
        report.add(Kind.MAPPED, w, "Mode `fallback`: the route fails over to the next target in order.");
        if (balancedBackup) {
            report.add(Kind.CHANGED, w, "A backup that balanced several providers became those providers in order:"
                    + " a chain tries one target after another.");
        }
        Object codes = strategy.containsKey("on_status_codes") ? strategy.get("on_status_codes")
                : node.get("on_status_codes");
        String gateway = "The gateway fails over on server errors, timeouts, rate limits (429) and open circuits; a"
                + " request the provider refuses (400, 401, 403, 404) does not fail over.";
        if (codes instanceof List<?> list) {
            String listed = list.stream().map(String::valueOf).collect(Collectors.joining(", "));
            report.add(Kind.CHANGED, w, "on_status_codes [" + listed + "]: Portkey failed over only on these codes. "
                    + gateway + " The list cannot be set.");
            if (list.stream().anyMatch(c -> String.valueOf(c).equals("246") || String.valueOf(c).equals("446"))) {
                report.add(Kind.CHANGED, w, "246 and 446 are Portkey's guardrail verdicts. A request the gateway's"
                        + " guardrail blocks is refused, not failed over.");
            }
        } else {
            report.add(Kind.CHANGED, w, "No on_status_codes: Portkey failed over on any error response, 4xx"
                    + " included. " + gateway);
        }
        return new Plan(first.primary(), backups);
    }

    private Plan loadbalance(String w, List<Map<String, Object>> targets, List<String> paths, Inherited inh) {
        List<Plan> plans = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Double weight = targets.get(i).containsKey("weight") ? number(targets.get(i).get("weight")) : Double.valueOf(1);
            if (weight == null || weight < 0) {
                report.add(Kind.NOT_MAPPED, where(paths.get(i)), "weight is not a number of zero or more; the target"
                        + " is not served.");
                continue;
            }
            if (weight == 0) {
                report.add(Kind.MAPPED, where(paths.get(i)), "weight 0: Portkey never chose it, so it is not served.");
                continue;
            }
            Plan p = compile(targets.get(i), paths.get(i), inh);
            if (p != null) {
                plans.add(p);
                weights.add(weight);
            }
        }
        if (plans.isEmpty()) {
            return null;
        }
        String model = plans.get(0).model();
        List<Leaf> primary = new ArrayList<>();
        List<Leaf> backups = new ArrayList<>();
        List<Leaf> nestedBackups = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            Plan p = plans.get(i);
            if (Objects.equals(p.model(), model)) {
                double total = p.primary().stream().mapToDouble(Leaf::weight).sum();
                for (Leaf l : p.primary()) {
                    primary.add(new Leaf(l.where(), l.type(), l.model(), weights.get(i) * l.weight() / total));
                }
            } else {
                backups.addAll(p.primary());
            }
            nestedBackups.addAll(p.fallbacks());
        }
        backups.addAll(nestedBackups);
        if (primary.stream().map(Leaf::type).distinct().count() > 1) {
            report.add(Kind.MAPPED, w, "Mode `loadbalance`: a weighted route, by each target's weight.");
        } else if (backups.size() == nestedBackups.size()) {
            report.add(Kind.MAPPED, w, "Mode `loadbalance` with one provider: it takes all the traffic.");
        }
        if (backups.size() > nestedBackups.size()) {
            report.add(Kind.CHANGED, w, "Its targets are sent different models (" + plans.stream()
                    .map(p -> Objects.toString(p.model(), "the caller's model")).distinct()
                    .collect(Collectors.joining(", ")) + "). A route sends one model, so the targets sent `"
                    + Objects.toString(model, "the caller's model") + "` share the traffic and the others became"
                    + " backups: load balancing became failover.");
        }
        if (!nestedBackups.isEmpty()) {
            report.add(Kind.CHANGED, w, "A balanced target had backups of its own. They became backups of the whole"
                    + " route, tried after the balanced targets.");
        }
        return new Plan(primary, backups);
    }

    private Plan nestedConditional(String w, Map<String, Object> strategy, List<Map<String, Object>> targets,
                                   List<String> paths, Inherited inh) {
        anyMetadataCondition = true;
        Object def = strategy.get("default");
        int index = indexOfTarget(targets, def);
        report.add(Kind.NOT_MAPPED, w, "Conditional routing inside another strategy cannot be expressed: a route is"
                + " chosen by the model a request names, once.");
        if (index < 0) {
            report.add(Kind.ACTION, w, "Not served: it has no default target. Move the conditional group to the top"
                    + " of the config, or name a default, and run the importer again.");
            return null;
        }
        report.add(Kind.ACTION, w, "Every request now goes to its default target `" + def + "`, whatever the"
                + " conditions said. Check that is acceptable.");
        return compile(targets.get(index), paths.get(index), inh);
    }

    /**
     * A conditional config. Conditions on the model a request names become routes for that name, which is
     * what they did in Portkey. Conditions on anything else cannot be expressed: their target gets a route of
     * its own name, and the report says how callers reach it. The default target is the config's main plan.
     */
    private Plan rootConditional(String id, Map<String, Object> root, Map<String, Object> strategy) {
        String w = where("");
        Inherited inh = inherit(Inherited.NONE, root, w);
        readNodeSettings(root, w, true);
        for (String k : strategy.keySet()) {
            if (!Set.of("mode", "conditions", "default", "on_status_codes").contains(k)) {
                report.add(Kind.NOT_MAPPED, w, "strategy." + k + " is not carried over.");
            }
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        if (root.get("targets") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof Map<?, ?> m) {
                    targets.add(node(stringKeys(m), "targets[" + i + "]"));
                    paths.add("targets[" + i + "]");
                }
            }
        }
        Map<Integer, Plan> compiled = new LinkedHashMap<>();
        Set<Integer> used = new LinkedHashSet<>();
        java.util.function.IntFunction<Plan> plan = i -> {
            used.add(i);
            return compiled.computeIfAbsent(i, k -> {
                Plan p = compile(targets.get(k), paths.get(k), inh);
                return p == null ? new Plan(List.of(), List.of()) : p;
            });
        };

        List<?> conditions = strategy.get("conditions") instanceof List<?> l ? l : List.of();
        if (conditions.isEmpty()) {
            report.add(Kind.NOT_MAPPED, w, "Conditional strategy with no conditions.");
        }
        for (int c = 0; c < conditions.size(); c++) {
            String cw = where("strategy.conditions[" + c + "]");
            if (!(conditions.get(c) instanceof Map<?, ?> cm)) {
                report.add(Kind.NOT_MAPPED, cw, "Not a JSON object; not read.");
                continue;
            }
            Map<String, Object> condition = stringKeys(cm);
            Object then = condition.get("then");
            int index = indexOfTarget(targets, then);
            if (index < 0) {
                report.add(Kind.NOT_MAPPED, cw, "`then` names no target (`" + then + "`); not read.");
                continue;
            }
            Plan p = plan.apply(index);
            if (p.primary().isEmpty()) {
                report.add(Kind.NOT_MAPPED, cw, "Its target `" + then + "` could not be served.");
                continue;
            }
            List<String> models = modelQuery(condition.get("query"));
            if (models != null) {
                for (String m : models) {
                    if (routesByModel.containsKey(m)) {
                        report.add(Kind.NOT_MAPPED, cw, "Model `" + m + "` already has a route, from an earlier"
                                + " condition; this one is not used, as Portkey takes the first match.");
                        continue;
                    }
                    addRoute(m, p, cw);
                    report.add(Kind.MAPPED, cw, "Requests for model `" + m + "` go to target `" + then + "`: route `"
                            + routesByModel.get(m).id() + "`." + (p.model() == null || p.model().equals(m) ? ""
                            : " The provider is sent `" + p.model() + "`."));
                }
                continue;
            }
            anyMetadataCondition = true;
            if (Objects.equals(String.valueOf(then), String.valueOf(strategy.get("default")))) {
                report.add(Kind.CHANGED, cw, "The condition " + describeQuery(condition.get("query")) + " cannot be"
                        + " expressed, but its target `" + then + "` is the default, which serves every request no"
                        + " route matches: those requests go where they went.");
                continue;
            }
            report.add(Kind.NOT_MAPPED, cw, "The condition " + describeQuery(condition.get("query")) + " cannot be"
                    + " expressed: the gateway chooses a route by the model a request names, not by metadata,"
                    + " other parameters or the path.");
            String name = routeName(id, String.valueOf(then));
            if (p.model() == null) {
                report.add(Kind.ACTION, cw, "Requests it sent to `" + then + "` now go to the default target. `" + then
                        + "` sends the caller's own model, so it cannot get a route of its own; add"
                        + " override_params.model to it if those callers need it.");
            } else if (routesByModel.containsKey(name)) {
                report.add(Kind.ACTION, cw, "Requests it sent to `" + then + "` now go to the default target. Callers"
                        + " that should reach `" + then + "` send `model: \"" + name + "\"`.");
            } else {
                addRoute(name, p, cw);
                report.add(Kind.ACTION, cw, "Requests it sent to `" + then + "` now go to the default target. Route `"
                        + routesByModel.get(name).id() + "` serves `" + then + "`: have the callers that matched this"
                        + " condition send `model: \"" + name + "\"`.");
            }
        }

        Object def = strategy.get("default");
        Plan main = null;
        if (def == null) {
            report.add(Kind.CHANGED, w, "No default target: Portkey refused a request that matched no condition."
                    + (multi ? "" : " The gateway refuses it too, with policy `portkey-served-models`."));
        } else {
            int index = indexOfTarget(targets, def);
            if (index < 0) {
                report.add(Kind.NOT_MAPPED, w, "strategy.default names no target (`" + def + "`).");
            } else {
                Plan p = plan.apply(index);
                if (!p.primary().isEmpty()) {
                    main = p;
                    report.add(Kind.MAPPED, w, "strategy.default: target `" + def + "` serves every request no"
                            + " condition matches.");
                }
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            if (!used.contains(i)) {
                report.add(Kind.NOT_MAPPED, where(paths.get(i)), "No condition or default names this target, so"
                        + " Portkey never chose it; not served.");
            }
        }
        return main;
    }

    private static int indexOfTarget(List<Map<String, Object>> targets, Object name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < targets.size(); i++) {
            if (String.valueOf(name).equals(targets.get(i).get("name"))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The model names a condition matches when it tests only the request's model by equality:
     * {@code {"params.model": "x"}}, {@code {"$eq": "x"}} or {@code {"$in": [...]}}. Null for anything else.
     */
    static List<String> modelQuery(Object query) {
        if (!(query instanceof Map<?, ?> q) || q.size() != 1 || !q.containsKey("params.model")) {
            return null;
        }
        Object v = q.get("params.model");
        if (v instanceof String s) {
            return List.of(s);
        }
        if (v instanceof Map<?, ?> op && op.size() == 1) {
            if (op.get("$eq") instanceof String s) {
                return List.of(s);
            }
            if (op.get("$in") instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(x -> x instanceof String)) {
                return l.stream().map(String::valueOf).toList();
            }
        }
        return null;
    }

    private static String describeQuery(Object query) {
        Set<String> fields = new TreeSet<>();
        collectFields(query, fields);
        return fields.isEmpty() ? "" : "on " + fields.stream().map(f -> "`" + f + "`").collect(Collectors.joining(", "));
    }

    private static void collectFields(Object query, Set<String> fields) {
        if (query instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (key.startsWith("$")) {
                    collectFields(v, fields);
                } else {
                    fields.add(key);
                }
            });
        } else if (query instanceof List<?> l) {
            l.forEach(x -> collectFields(x, fields));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // One target
    // ------------------------------------------------------------------------------------------------

    /** Keys a target uses only to reach its provider. */
    private static final Set<String> AZURE_KEYS = Set.of("resource_name", "deployment_id", "api_version",
            "azure_auth_mode", "azure_model_name", "azure_api_version", "azure_deployment_name");
    private static final Set<String> AWS_KEYS = Set.of("aws_access_key_id", "aws_secret_access_key", "aws_region",
            "aws_session_token", "aws_auth_type", "aws_role_arn", "aws_external_id");

    private Plan leaf(Map<String, Object> node, String w, Inherited inh) {
        if (Boolean.TRUE.equals(node.get("passthrough"))) {
            report.add(Kind.CHANGED, w, "passthrough: the caller chose the provider with x-portkey-provider or an"
                    + " `@slug/model` name. The gateway has no such header; a request no route matches goes to the"
                    + " provider that serves its model by name, among those gateway.yaml configures.");
            return null;
        }
        String provider = str(node.get("provider"));
        String virtualKey = str(node.get("virtual_key"));
        String slug = null;
        String slugModel = null;
        if (provider != null && provider.startsWith("@")) {
            int slash = provider.indexOf('/');
            slug = slash > 0 ? provider.substring(0, slash) : provider;
            slugModel = slash > 0 ? provider.substring(slash + 1) : null;
            provider = null;
        }
        String vaultRef = slug != null ? slug : virtualKey;
        String model = str(inh.overrideParams().get("model"));
        if (model == null) {
            model = slugModel;
        }

        ProviderType type;
        if (provider != null) {
            type = ProviderType.forPortkeyId(provider).orElse(null);
            if (type == null) {
                String reason = ProviderType.UNSUPPORTED_REASONS.getOrDefault(provider.toLowerCase(Locale.ROOT),
                        "The open gateway has no provider for `" + provider + "`. If it serves an OpenAI-compatible"
                                + " API, write it as provider `openai` with its custom_host and run the importer"
                                + " again.");
                report.add(Kind.NOT_MAPPED, w, "Provider `" + provider + "` is not served. " + reason);
                return null;
            }
        } else if (vaultRef != null) {
            String hint = model;
            type = ProviderType.fromName(vaultRef).or(() -> ProviderType.fromModel(hint)).orElse(null);
            if (type == null) {
                report.add(Kind.NOT_MAPPED, w, "Not served: `" + vaultRef + "` is a Portkey "
                        + (slug != null ? "Model Catalog provider" : "virtual key") + ", whose provider is stored in"
                        + " Portkey's cloud, and neither its name nor the model says which provider it is.");
                report.add(Kind.ACTION, w, "Add `\"provider\": \"<portkey provider id>\"` (such as openai or"
                        + " anthropic) next to `" + vaultRef + "` and run the importer again.");
                return null;
            }
            if (inferredRefs.add(vaultRef)) {
                report.add(Kind.ACTION, w, "`" + vaultRef + "` lives in Portkey's cloud, so its provider was inferred"
                        + " from its name or model: `" + type.yamlType + "`. Check that is right.");
            }
        } else {
            report.add(Kind.NOT_MAPPED, w, "No provider, virtual_key or passthrough; nothing to call.");
            return null;
        }

        if (type == ProviderType.AZURE_OPENAI) {
            String deployment = str(node.get("deployment_id"));
            if (deployment == null) {
                deployment = str(node.get("azure_deployment_name"));
            }
            if (deployment != null) {
                model = deployment;
            } else if (model != null) {
                report.add(Kind.ACTION, w, "No deployment_id: the Azure deployment is in Portkey's vault. The gateway"
                        + " calls deployment `" + model + "` (the model name); change it in gateway.yaml if the"
                        + " deployment has another name.");
            } else {
                report.add(Kind.NOT_MAPPED, w, "Not served: no deployment_id and no model, so the Azure deployment"
                        + " to call is unknown (it is in Portkey's vault).");
                report.add(Kind.ACTION, w, "Add `deployment_id` or override_params.model to this target and run the"
                        + " importer again.");
                return null;
            }
        }
        if (model != null && (model.isBlank() || model.chars().anyMatch(Character::isWhitespace))) {
            report.add(Kind.NOT_MAPPED, w, "Not served: the model `" + model.strip() + "` is blank or has a space in"
                    + " it, which the gateway cannot send.");
            return null;
        }
        String dvaraModel = model == null ? null : type.dvaraModel(model);

        // Checked before the provider is built, so a target that is not served adds no instructions.
        String fingerprint = fingerprint(type, node, inh, vaultRef);
        String existing = providerFingerprints.get(type);
        if (existing != null && !existing.equals(fingerprint)) {
            report.add(Kind.NOT_MAPPED, w, "Not served. It uses a different " + type.yamlType + " key, vault entry"
                    + " or URL than " + providerSources.get(type).get(0) + ", and the open gateway has one "
                    + type.yamlType + " endpoint. (A workspace can bring its own key under `credentials`.)");
            return null;
        }
        if (existing == null) {
            Provider p = providerFor(w, type, node, inh, vaultRef);
            if (p == null) {
                return null;
            }
            providerFingerprints.put(type, fingerprint);
            out.providers.put(type, p);
        }
        providerSources.computeIfAbsent(type, t -> new ArrayList<>()).add(w);

        for (String k : node.keySet()) {
            if ((AZURE_KEYS.contains(k) && type != ProviderType.AZURE_OPENAI)
                    || (AWS_KEYS.contains(k) && type != ProviderType.BEDROCK)) {
                report.add(Kind.NOT_MAPPED, w, "`" + k + "` is not used by provider " + type.yamlType + ".");
            }
        }
        recordResilience(type, w, inh);
        return new Plan(List.of(new Leaf(w, type, dvaraModel, 1)), List.of());
    }

    /** The provider entry for a target; null, with a report line, when it cannot be built. */
    private Provider providerFor(String w, ProviderType type, Map<String, Object> node, Inherited inh,
                                 String vaultRef) {
        // First, so a target that is not served adds no instructions.
        UrlValue baseUrl = baseUrl(w, type, node, inh, vaultRef);
        if (baseUrl == UNUSABLE_BASE_URL) {
            return null;
        }

        SecretValue apiKey = null;
        Object rawKey = node.get("api_key");
        if (type.takesApiKey) {
            if (rawKey != null) {
                apiKey = secret(w, "api_key", rawKey, type.keyEnv);
            } else if (vaultRef != null) {
                apiKey = new SecretValue(type.keyEnv);
                envNames.add(type.keyEnv);
                report.add(Kind.ACTION, w, "The " + type.yamlType + " key is in Portkey's vault under `" + vaultRef
                        + "`, which the importer cannot read. Copy it from Portkey and set " + type.keyEnv + ".");
            } else {
                apiKey = new SecretValue(type.keyEnv);
                envNames.add(type.keyEnv);
                report.add(Kind.ACTION, w, "No api_key: Portkey sent the key each caller passed in Authorization. The"
                        + " gateway holds the provider key itself: set " + type.keyEnv + ", and give callers DVARA"
                        + " keys instead.");
            }
        } else if (rawKey != null) {
            report.add(Kind.NOT_MAPPED, w, "api_key: the open gateway calls " + type.yamlType + " without a key, so it"
                    + " is not carried over.");
        }

        if (baseUrl != null && baseUrl.isLiteral() && inh.customHost() != null
                && !isCopyableUrl(inh.customHost().strip())) {
            report.add(Kind.ACTION, w, "custom_host carries a user and password or a query string. They were not"
                    + " copied: base_url is " + baseUrl.literal() + ". "
                    + (apiKey != null ? "The gateway sends only the provider key, as a bearer token, so put a key"
                    + " the server accepts in " + apiKey.envName() + "."
                    : "The gateway calls " + type.yamlType + " without a key, so the server must accept calls"
                    + " without them.")
                    + " Rotate the credentials, since they were stored in plain text.");
        }
        if (type == ProviderType.OPENAI && baseUrl != null) {
            report.add(Kind.CHANGED, w, "A server of its own (custom_host). It is served through the gateway's"
                    + " openai provider, at that base URL, which then serves every OpenAI route.");
        }

        SecretValue accessKey = null;
        SecretValue secretKey = null;
        UrlValue region = null;
        if (type == ProviderType.AZURE_OPENAI) {
            if (node.get("api_version") != null || node.get("azure_api_version") != null) {
                report.add(Kind.CHANGED, w, "api_version is not carried over: the gateway calls Azure OpenAI chat"
                        + " with api-version 2024-10-21.");
            }
            if (node.get("azure_auth_mode") != null) {
                report.add(Kind.ACTION, w, "azure_auth_mode `" + node.get("azure_auth_mode") + "`: the gateway"
                        + " authenticates to Azure OpenAI with an API key only. Set " + type.keyEnv + " to a key"
                        + " for the resource.");
            }
            if (node.get("azure_model_name") != null) {
                report.add(Kind.NOT_MAPPED, w, "azure_model_name is not carried over; the gateway names an Azure"
                        + " model by its deployment.");
            }
        }
        if (type == ProviderType.BEDROCK) {
            accessKey = secretOrNull(w, "aws_access_key_id", node.get("aws_access_key_id"), "AWS_ACCESS_KEY_ID");
            secretKey = secretOrNull(w, "aws_secret_access_key", node.get("aws_secret_access_key"),
                    "AWS_SECRET_ACCESS_KEY");
            if (accessKey == null) {
                accessKey = new SecretValue("AWS_ACCESS_KEY_ID");
                envNames.add("AWS_ACCESS_KEY_ID");
            }
            if (secretKey == null) {
                secretKey = new SecretValue("AWS_SECRET_ACCESS_KEY");
                envNames.add("AWS_SECRET_ACCESS_KEY");
            }
            if (node.get("aws_access_key_id") == null) {
                report.add(Kind.ACTION, w, (vaultRef != null ? "The AWS keys are in Portkey's vault under `" + vaultRef
                        + "`. " : "No AWS keys in the target. ") + "The gateway signs Bedrock calls with"
                        + " AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY: set them.");
            }
            Object r = node.get("aws_region");
            String regionEnv = SecretValue.envReference(r).filter(SecretValue::isValidName).orElse(null);
            if (regionEnv != null) {
                envNames.add(regionEnv);
                region = UrlValue.fromEnv(regionEnv, "");
            } else if (r instanceof String s && s.strip().matches("[a-z0-9-]+")) {
                region = UrlValue.literal(s.strip());
            } else {
                envNames.add("AWS_REGION");
                region = UrlValue.fromEnv("AWS_REGION", "");
                report.add(Kind.ACTION, w, "No usable aws_region in the target; the gateway reads AWS_REGION: set it.");
            }
            for (String k : List.of("aws_session_token", "aws_auth_type", "aws_role_arn", "aws_external_id")) {
                if (node.get(k) != null) {
                    report.add(Kind.ACTION, w, k + " is not carried over (its value is not copied): the gateway signs"
                            + " Bedrock calls with an access key and secret key only, not a session token or an"
                            + " assumed role. Give it long-lived keys for a user that may call Bedrock.");
                }
            }
        }
        return new Provider(type, apiKey, baseUrl, accessKey, secretKey, region);
    }

    /**
     * Returned by {@link #baseUrl} when the target names a server that cannot be written. Leaving the URL out
     * would send its traffic and key to the provider's public API instead, so it is not served.
     */
    private static final UrlValue UNUSABLE_BASE_URL = UrlValue.literal("");

    private static final Pattern AZURE_RESOURCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,62}");

    /** The provider's base URL; null for its own API; {@link #UNUSABLE_BASE_URL}, reported, if neither. */
    private UrlValue baseUrl(String w, ProviderType type, Map<String, Object> node, Inherited inh, String vaultRef) {
        String raw = inh.customHost();
        boolean azure = type == ProviderType.AZURE_OPENAI;
        String azureSuffix = "/openai";
        if (raw == null) {
            if (azure) {
                Object resource = node.get("resource_name");
                if (resource instanceof String r && AZURE_RESOURCE.matcher(r.strip()).matches()) {
                    return UrlValue.literal("https://" + r.strip() + ".openai.azure.com" + azureSuffix);
                }
                if (resource != null) {
                    report.add(Kind.ACTION, w, "Not served. resource_name is not an Azure resource name, and without"
                            + " a base URL the gateway cannot call Azure. Fix it and run the importer again.");
                    return UNUSABLE_BASE_URL;
                }
                envNames.add("AZURE_OPENAI_BASE");
                report.add(Kind.ACTION, w, "No resource_name" + (vaultRef != null ? " (the Azure resource is in"
                        + " Portkey's vault)" : "") + ". The gateway reads AZURE_OPENAI_BASE and adds /openai after it:"
                        + " set it without a trailing slash, such as https://<resource>.openai.azure.com.");
                return UrlValue.fromEnv("AZURE_OPENAI_BASE", azureSuffix);
            }
            return null;
        }
        String envName = SecretValue.envReference(raw).orElse(null);
        if (envName != null) {
            envNames.add(envName);
            if (azure) {
                report.add(Kind.ACTION, w, "gateway.yaml adds /openai after " + envName + ". Make sure it has no"
                        + " trailing slash.");
                return UrlValue.fromEnv(envName, azureSuffix);
            }
            if (type != ProviderType.OPENAI) {
                report.add(Kind.ACTION, w, "custom_host from " + envName + ". Check it is the form the gateway's "
                        + type.yamlType + " provider expects.");
            }
            return UrlValue.fromEnv(envName, "");
        }
        String url = raw.strip();
        if (!isCopyableUrl(url)) {
            // The credentials are left out (the report says how to supply them); the host is kept.
            url = withoutCredentials(url);
            if (url == null) {
                report.add(Kind.ACTION, w, "Not served. custom_host is not a URL the importer can read, and without a"
                        + " base URL the gateway would call " + type.yamlType + "'s own API. Fix custom_host and run"
                        + " the importer again. (It was not copied, in case it holds a credential.)");
                return UNUSABLE_BASE_URL;
            }
        }
        String trimmed = url.replaceAll("/+$", "");
        if (type == ProviderType.OPENAI && trimmed.equals("https://api.openai.com/v1")) {
            return null;
        }
        if (azure) {
            return UrlValue.literal(trimmed.endsWith(azureSuffix) ? trimmed : trimmed + azureSuffix);
        }
        if ((type == ProviderType.ANTHROPIC || type == ProviderType.OLLAMA) && trimmed.endsWith("/v1")) {
            // Portkey's host for these ends in /v1; the gateway adds /v1/... itself.
            trimmed = trimmed.substring(0, trimmed.length() - 3);
            report.add(Kind.CHANGED, w, "custom_host ends in /v1, which the gateway's " + type.yamlType
                    + " provider adds itself: base_url is " + trimmed + ".");
            return UrlValue.literal(trimmed);
        }
        if (type != ProviderType.OPENAI && type != ProviderType.OLLAMA && type != ProviderType.ANTHROPIC) {
            report.add(Kind.ACTION, w, "custom_host copied as base_url. Check it is the form the gateway's "
                    + type.yamlType + " provider expects.");
        }
        return UrlValue.literal(url);
    }

    /** A URL that may be written to the output: http(s), a host, no user, no password and no query. */
    static boolean isCopyableUrl(String url) {
        try {
            URI uri = new URI(url);
            return uri.getScheme() != null && Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** The URL with its user, password, query and fragment removed; null if it is not http(s) with a host. */
    static String withoutCredentials(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null
                    || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                    + Objects.toString(uri.getRawPath(), "");
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** The variable a secret is read from; a literal value is not copied, and the default is named instead. */
    private SecretValue secret(String w, String field, Object raw, String defaultEnv) {
        SecretValue value = secretOrNull(w, field, raw, defaultEnv);
        if (value == null) {
            envNames.add(defaultEnv);
            return new SecretValue(defaultEnv);
        }
        return value;
    }

    private SecretValue secretOrNull(String w, String field, Object raw, String defaultEnv) {
        if (raw == null) {
            return null;
        }
        String envName = SecretValue.envReference(raw).orElse(null);
        if (envName != null) {
            envNames.add(envName);
            return new SecretValue(envName);
        }
        envNames.add(defaultEnv);
        report.add(Kind.ACTION, w, field + " is written in the config as a literal. It was not copied. The gateway"
                + " reads " + defaultEnv + ": put the value there, and rotate it, since it was stored in plain text"
                + " (and, if callers sent this config in x-portkey-config, on every request).");
        return new SecretValue(defaultEnv);
    }

    /** What identifies one endpoint of a type. Held in memory only, to compare targets; never written. */
    private static String fingerprint(ProviderType type, Map<String, Object> node, Inherited inh, String vaultRef) {
        StringBuilder f = new StringBuilder(type.name());
        f.append('|').append(Objects.toString(node.get("api_key"), ""));
        f.append('|').append(Objects.toString(vaultRef, ""));
        f.append('|').append(Objects.toString(inh.customHost(), ""));
        for (String k : List.of("resource_name", "aws_access_key_id", "aws_secret_access_key", "aws_region")) {
            f.append('|').append(Objects.toString(node.get(k), ""));
        }
        return f.toString();
    }

    private void reportProviders() {
        providerSources.forEach((type, sources) -> {
            Provider p = out.providers.get(type);
            StringBuilder text = new StringBuilder("Provider `").append(type.yamlType).append("`");
            if (p.apiKey() != null) {
                text.append(", key from ").append(p.apiKey().yaml());
            }
            if (p.accessKey() != null) {
                text.append(", access key from ").append(p.accessKey().yaml());
            }
            if (p.baseUrl() != null) {
                text.append(p.baseUrl().isLiteral() ? ", at " + p.baseUrl().literal()
                        : ", base URL from ${" + p.baseUrl().envName() + "}");
            }
            text.append('.');
            report.add(Kind.MAPPED, String.join(", ", new LinkedHashSet<>(sources)), text.toString());
        });
    }

    // ------------------------------------------------------------------------------------------------
    // What a target takes from its group, and the settings on each target or group
    // ------------------------------------------------------------------------------------------------

    private Inherited inherit(Inherited parent, Map<String, Object> node, String w) {
        Map<String, Object> override = new LinkedHashMap<>(parent.overrideParams());
        if (node.containsKey("override_params")) {
            Map<String, Object> own = map(node.get("override_params"));
            if (own == null) {
                report.add(Kind.NOT_MAPPED, w, "override_params is not a JSON object; not read.");
            } else {
                override.putAll(own);
                reportOverrideParams(w, own);
            }
        }
        Map<String, Object> retry = parent.retry();
        if (node.containsKey("retry")) {
            Map<String, Object> own = map(node.get("retry"));
            if (own == null) {
                report.add(Kind.NOT_MAPPED, w, "retry is not a JSON object; not read.");
            } else {
                retry = own;
                reportRetry(w, own);
            }
        }
        Object timeout = node.containsKey("request_timeout") ? node.get("request_timeout") : parent.requestTimeout();
        if (node.containsKey("request_timeout")) {
            Double ms = number(node.get("request_timeout"));
            if (ms == null || ms <= 0) {
                report.add(Kind.NOT_MAPPED, w, "request_timeout is not a positive number of milliseconds; not read.");
                timeout = parent.requestTimeout();
            }
        }
        String host = parent.customHost();
        if (node.containsKey("custom_host")) {
            host = node.get("custom_host") instanceof String s && !s.isBlank() ? s : null;
            if (host == null) {
                report.add(Kind.NOT_MAPPED, w, "custom_host is empty; not read.");
            }
        }
        return new Inherited(override, retry, timeout, host);
    }

    private void reportOverrideParams(String w, Map<String, Object> own) {
        for (Map.Entry<String, Object> e : own.entrySet()) {
            switch (e.getKey()) {
                case "model" -> { }
                case "max_tokens", "max_completion_tokens" -> {
                    Integer n = integer(e.getValue());
                    report.add(Kind.NOT_MAPPED, w, "override_params." + e.getKey() + ": the gateway does not rewrite"
                            + " a request's parameters.");
                    report.add(Kind.ACTION, w, "override_params." + e.getKey() + " capped every response"
                            + (n != null ? " at " + n + " tokens" : "") + ". To keep a cap, add a policy rule with"
                            + " `max_tokens: {limit: " + (n != null ? n : "<n>") + "}`, which refuses larger requests,"
                            + " and set the workspace's guardrail.default-max-response-tokens for requests that send"
                            + " none.");
                }
                default -> report.add(Kind.NOT_MAPPED, w, "override_params." + e.getKey() + ": the gateway sends the"
                        + " caller's parameters and does not rewrite them.");
            }
        }
    }

    private void reportRetry(String w, Map<String, Object> retry) {
        Integer attempts = integer(retry.get("attempts"));
        if (attempts == null || attempts < 0) {
            report.add(Kind.NOT_MAPPED, w, "retry.attempts is not a whole number; the retry is not carried over.");
            return;
        }
        if (retry.get("on_status_codes") instanceof List<?> codes) {
            Set<String> given = codes.stream().map(String::valueOf).collect(Collectors.toCollection(TreeSet::new));
            Set<String> standard = PORTKEY_RETRY_CODES.stream().map(String::valueOf)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!given.equals(standard)) {
                report.add(Kind.CHANGED, w, "retry.on_status_codes [" + String.join(", ", given) + "]: the gateway"
                        + " retries connection errors, timeouts, 408, 429 and 5xx, and the list cannot be set.");
            }
        }
        if (retry.containsKey("use_retry_after_header") || retry.containsKey("use_retry_after_headers")) {
            report.add(Kind.CHANGED, w, "use_retry_after_header: the gateway waits by its own backoff"
                    + " (dvara.llm-gateway.resilience.retry.initial-backoff-ms, doubling up to max-backoff-ms).");
        }
        for (String k : retry.keySet()) {
            if (!Set.of("attempts", "on_status_codes", "use_retry_after_header", "use_retry_after_headers").contains(k)) {
                report.add(Kind.NOT_MAPPED, w, "retry." + k + " is not carried over.");
            }
        }
    }

    private void recordResilience(ProviderType type, String w, Inherited inh) {
        Integer retries = inh.retry() == null ? Integer.valueOf(0) : integer(inh.retry().get("attempts"));
        Double ms = number(inh.requestTimeout());
        resilience.add(new Resilience(type, w, retries == null || retries < 0 ? 0 : retries,
                ms == null || ms <= 0 ? null : Math.round(ms)));
    }

    /** Keys a target or group may carry, read by {@link #compile}, {@link #leaf} and {@link #inherit}. */
    private static final Set<String> NODE_KEYS = Set.of("strategy", "targets", "name", "provider", "virtual_key",
            "api_key", "custom_host", "override_params", "weight", "retry", "request_timeout", "on_status_codes",
            "passthrough", "cache", "input_guardrails", "output_guardrails", "before_request_hooks",
            "after_request_hooks", "default_input_guardrails", "default_output_guardrails", "input_mutators",
            "output_mutators");

    private void readNodeSettings(Map<String, Object> node, String w, boolean root) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String key = e.getKey();
            if (NODE_KEYS.contains(key) || AZURE_KEYS.contains(key) || AWS_KEYS.contains(key)) {
                continue;
            }
            switch (key) {
                case "forward_headers" -> report.add(Kind.NOT_MAPPED, w, "forward_headers: the gateway sends the"
                        + " provider its own headers; it does not forward the caller's.");
                case "default_params" -> report.add(Kind.NOT_MAPPED, w, "default_params: the gateway sets no request"
                        + " defaults per target. (A workspace's guardrail.default-max-response-tokens sets a default"
                        + " max_tokens.)");
                case "drop_params" -> report.add(Kind.NOT_MAPPED, w, "drop_params: the gateway does not remove request"
                        + " fields per target; it translates each request for the provider that serves it.");
                case "strict_open_ai_compliance" -> report.add(Kind.NOT_MAPPED, w, "strict_open_ai_compliance: no"
                        + " setting; not carried over.");
                case "openai_organization", "openai_project" -> report.add(Kind.NOT_MAPPED, w, key + ": the gateway"
                        + " sends OpenAI no organization or project header.");
                case "id", "cb_config" -> report.add(Kind.CHANGED, w, key + ": Portkey's circuit breaker. The gateway"
                        + " has its own per provider: dvara.llm-gateway.resilience.circuit-breaker.*.");
                case "prompt_id" -> report.add(Kind.NOT_MAPPED, w, "prompt_id names a prompt kept in Portkey's cloud;"
                        + " not carried over. The gateway has prompt templates of its own (prompt_templates).");
                case "auto_caching" -> report.add(Kind.NOT_MAPPED, w, "auto_caching: the open gateway has no response"
                        + " cache.");
                case "metadata" -> report.add(Kind.NOT_MAPPED, w, "metadata is not carried over.");
                case "vertex_project_id", "vertex_region", "vertex_service_account_json", "vertex_storage_bucket_name"
                        -> report.add(Kind.NOT_MAPPED, w, key + " is not carried over (its value is not copied): the"
                        + " gateway does not call Vertex AI.");
                default -> report.add(Kind.NOT_MAPPED, w, "`" + key + "` is not carried over.");
            }
        }
        if (node.containsKey("cache")) {
            Map<String, Object> cache = map(node.get("cache"));
            String mode = cache != null ? String.valueOf(cache.get("mode")) : String.valueOf(node.get("cache"));
            report.add(Kind.NOT_MAPPED, w, "cache (" + mode + "): the open gateway has no response cache, simple or"
                    + " semantic. Every request goes to the provider.");
        }
        readHooks(node, w, root);
    }

    // ------------------------------------------------------------------------------------------------
    // Guardrails and hooks
    // ------------------------------------------------------------------------------------------------

    /** Checks the gateway's own PII detection stands in for. */
    private static final Set<String> PII_CHECKS = Set.of("portkey.pii", "pangea.pii", "patronus.pii", "patronus.phi",
            "promptfoo.pii", "azure.pii", "qualifire.pii");
    /** Checks the gateway's own prompt-injection guardrail stands in for. */
    private static final Set<String> INJECTION_CHECKS = Set.of("azure.shieldPrompt", "qualifire.promptInjections",
            "promptfoo.guard");

    private static final List<String> PII_STRICTNESS = List.of("LOG", "REDACT", "TOKENIZE", "BLOCK");
    private static final List<String> GUARDRAIL_STRICTNESS = List.of("LOG", "FLAG", "BLOCK");

    private void readHooks(Map<String, Object> node, String w, boolean root) {
        for (String key : List.of("input_guardrails", "default_input_guardrails", "before_request_hooks",
                "input_mutators", "output_guardrails", "default_output_guardrails", "after_request_hooks",
                "output_mutators")) {
            if (!node.containsKey(key)) {
                continue;
            }
            boolean input = key.startsWith("input") || key.startsWith("default_input") || key.startsWith("before");
            String kw = w + " " + key;
            if (!(node.get(key) instanceof List<?> list)) {
                report.add(Kind.NOT_MAPPED, kw, "Not a list; not read.");
                continue;
            }
            if (!root) {
                report.add(Kind.CHANGED, kw, "Set on one target. The gateway's guardrails are set per workspace, so"
                        + " what maps here applies to every request in it.");
            }
            for (int i = 0; i < list.size(); i++) {
                readHook(kw + "[" + i + "]", list.get(i), input, key.endsWith("mutators"));
            }
        }
    }

    private void readHook(String w, Object hook, boolean input, boolean mutator) {
        if (hook instanceof String id) {
            cloudGuardrail(w, id);
            return;
        }
        if (!(hook instanceof Map<?, ?> m)) {
            report.add(Kind.NOT_MAPPED, w, "Not a guardrail id or object; not read.");
            return;
        }
        Map<String, Object> h = stringKeys(m);
        boolean deny = Boolean.TRUE.equals(h.get("deny"));
        boolean async = Boolean.TRUE.equals(h.get("async"));
        // A failed check blocks a request only when the hook runs in line and denies (Portkey's 446).
        boolean enforcing = deny && !async;
        List<Map.Entry<String, Map<String, Object>>> checks = new ArrayList<>();
        if (h.get("checks") instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> cm) {
                    Map<String, Object> check = stringKeys(cm);
                    Map<String, Object> params = map(check.get("parameters"));
                    checks.add(Map.entry(checkId(String.valueOf(check.get("id"))), params == null ? Map.of() : params));
                    if (Boolean.FALSE.equals(check.get("is_enabled"))) {
                        checks.removeLast();
                    }
                }
            }
        } else {
            for (Map.Entry<String, Object> e : h.entrySet()) {
                if (Set.of("deny", "on_fail", "on_success", "async", "id", "type", "guardrail_version_id",
                        "sequential", "name").contains(e.getKey())) {
                    continue;
                }
                Map<String, Object> params = map(e.getValue());
                if (params != null && Boolean.FALSE.equals(params.get("is_enabled"))) {
                    continue;
                }
                checks.add(Map.entry(checkId(e.getKey()), params == null ? Map.of() : params));
            }
        }
        if (checks.isEmpty()) {
            if (h.get("id") instanceof String id) {
                cloudGuardrail(w, id);
            } else {
                report.add(Kind.NOT_MAPPED, w, "A guardrail with no checks; nothing to carry over.");
            }
            return;
        }
        if (h.containsKey("on_fail") || h.containsKey("on_success")) {
            report.add(Kind.NOT_MAPPED, w, "on_fail/on_success feedback is Portkey's logging; not carried over. The"
                    + " gateway's audit log records each detection.");
        }
        for (var check : checks) {
            readCheck(w + " " + check.getKey(), check.getKey(), check.getValue(), input, enforcing, mutator);
        }
    }

    private void readCheck(String w, String id, Map<String, Object> params, boolean input, boolean enforcing,
                           boolean mutator) {
        String verdict = enforcing ? "blocked a request that failed it" : "only recorded its verdict (deny was not"
                + " set, or it ran async)";
        if (PII_CHECKS.contains(id)) {
            boolean redact = Boolean.TRUE.equals(params.get("redact")) || mutator;
            String action = redact ? "REDACT" : enforcing ? "BLOCK" : "LOG";
            stricter("pii.action", action, PII_STRICTNESS);
            if (!input) {
                workspaceMetadata.put("pii.scan-responses", Boolean.TRUE);
            }
            report.add(Kind.MAPPED, w, "The gateway's own PII detection, with pii.action: " + action
                    + (input ? "" : " and pii.scan-responses: true") + ". It does not call " + id + ".");
            if (params.containsKey("categories") || params.containsKey("entities")) {
                report.add(Kind.CHANGED, w, "The categories chosen are not carried over: the gateway detects its own"
                        + " set of PII types (pii.custom-patterns adds more).");
            }
            if (!input) {
                report.add(Kind.CHANGED, w, "One pii.action covers requests and responses; Portkey set them apart.");
            }
            return;
        }
        if (INJECTION_CHECKS.contains(id) && input) {
            String action = enforcing ? "BLOCK" : "LOG";
            stricter("guardrail.action", action, GUARDRAIL_STRICTNESS);
            report.add(Kind.MAPPED, w, "The gateway's own prompt-injection guardrail, with guardrail.action: " + action
                    + ". It does not call " + id + ".");
            return;
        }
        if (id.equals("default.modelwhitelist") && input) {
            List<String> models = params.get("models") instanceof List<?> l
                    ? l.stream().filter(x -> x instanceof String).map(String::valueOf).toList() : List.of();
            boolean not = Boolean.TRUE.equals(params.get("not"));
            if (models.isEmpty()) {
                report.add(Kind.NOT_MAPPED, w, "No models listed; nothing to carry over.");
            } else if (!enforcing) {
                report.add(Kind.NOT_MAPPED, w, "It " + verdict + ", so no policy was written. The audit log records"
                        + " the model of every request.");
            } else {
                modelChecks.add(new ModelCheck(w, models, not));
            }
            return;
        }
        report.add(Kind.NOT_MAPPED, w, "`" + id + "` is not run: the open gateway has no equivalent check. It runs"
                + " its own PII detection and prompt-injection guardrail on every request.");
        if (enforcing) {
            report.add(Kind.ACTION, w, "In Portkey this check " + verdict + ". The gateway does not run it, so those"
                    + " requests now go through. Put an equivalent check in front of the gateway, or accept the"
                    + " change.");
        }
    }

    private void cloudGuardrail(String w, String id) {
        report.add(Kind.NOT_MAPPED, w, "Guardrail `" + id + "` is saved in Portkey's cloud; its checks and actions"
                + " are not in this file.");
        report.add(Kind.ACTION, w, "Open guardrail `" + id + "` in the Portkey app. If it blocked requests (deny),"
                + " set the matching workspace setting in gateway.yaml (pii.action: BLOCK for PII, guardrail.action:"
                + " BLOCK for prompt injection), or put an equivalent check in front of the gateway.");
    }

    private void stricter(String key, String action, List<String> order) {
        workspaceMetadata.merge(key, action, (a, b) -> order.indexOf(String.valueOf(a)) >= order.indexOf(
                String.valueOf(b)) ? a : b);
    }

    private static String checkId(String id) {
        return id.contains(".") ? id : "default." + id;
    }

    // ------------------------------------------------------------------------------------------------
    // Routes, retries and timeouts
    // ------------------------------------------------------------------------------------------------

    private void addRoute(String model, Plan plan, String w) {
        String pinned = Objects.equals(plan.model(), model) ? null : plan.model();
        List<WeightedProvider> providers = weights(plan.primary());
        List<Fallback> chain = new ArrayList<>();
        boolean unnamed = false;
        for (Leaf l : plan.fallbacks()) {
            Fallback f = new Fallback(l.type(), l.model());
            boolean isPrimary = Objects.equals(l.model(), plan.model())
                    && providers.stream().anyMatch(p -> p.type() == l.type());
            if (isPrimary || chain.contains(f)) {
                continue;
            }
            if (chain.size() >= MAX_FALLBACKS) {
                report.add(Kind.NOT_MAPPED, l.where(), "Backup " + l.type().yamlType + "/" + Objects.toString(
                        l.model(), "(caller's model)") + " dropped: a route's chain names at most " + MAX_FALLBACKS
                        + " backups.");
                continue;
            }
            unnamed |= l.model() == null && plan.model() != null;
            chain.add(f);
        }
        if (unnamed) {
            report.add(Kind.CHANGED, w, "A backup has no override_params.model. Portkey sent it the caller's model;"
                    + " the gateway sends it the request's model after the route's pin. Give the backup a model if"
                    + " it needs another.");
        }
        String id = model.equals(CATCH_ALL) ? routeId("all-models") : routeId(model);
        routesByModel.put(model, new RouteDraft(id, model, pinned, providers, List.copyOf(chain)));
    }

    /** One entry per provider type, its weight the sum of its targets'; whole numbers, at least 1. */
    private static List<WeightedProvider> weights(List<Leaf> leaves) {
        Map<ProviderType, Double> byType = new LinkedHashMap<>();
        for (Leaf l : leaves) {
            byType.merge(l.type(), l.weight(), Double::sum);
        }
        if (byType.size() == 1) {
            return List.of(new WeightedProvider(byType.keySet().iterator().next(), 1));
        }
        boolean fractional = byType.values().stream().anyMatch(w -> w != Math.rint(w));
        List<WeightedProvider> result = new ArrayList<>();
        byType.forEach((type, w) -> result.add(new WeightedProvider(type,
                (int) Math.max(1, Math.round(fractional ? w * 100 : w)))));
        return result;
    }

    private String routeId(String model) {
        String base = routeIdBase(model);
        String id = base;
        for (int n = 2; !routeIds.add(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    private static String routeIdBase(String model) {
        String base = model.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return base.isEmpty() ? "route" : base;
    }

    private static String routeName(String configId, String target) {
        String t = target.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("(^-+|-+$)", "");
        return configId + "-" + (t.isEmpty() ? "target" : t);
    }

    private void writeResilience() {
        if (resilience.isEmpty()) {
            return;
        }
        Map<ProviderType, TreeSet<Integer>> retries = new LinkedHashMap<>();
        Map<ProviderType, Set<Long>> timeouts = new LinkedHashMap<>();
        for (Resilience r : resilience) {
            retries.computeIfAbsent(r.type(), t -> new TreeSet<>()).add(r.retries());
            timeouts.computeIfAbsent(r.type(), t -> new LinkedHashSet<>()).add(r.timeoutMs());
        }
        String where = "retry";
        Set<Integer> allRetries = retries.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        if (allRetries.equals(Set.of(0))) {
            report.add(Kind.CHANGED, where, "No retry: Portkey made one attempt. The gateway retries a failed call"
                    + " twice by default; set dvara.llm-gateway.resilience.retry.max-attempts=1 to match.");
        } else if (allRetries.size() == 1) {
            // The gateway counts attempts, the first call included; Portkey counts retries after it.
            property(where, RESILIENCE + "retry.max-attempts", String.valueOf(allRetries.iterator().next() + 1));
        } else {
            retries.forEach((type, values) -> {
                property(where + " (" + type.yamlType + ")", RESILIENCE + "providers." + type.yamlType
                        + ".retry.max-attempts", String.valueOf(values.last() + 1));
                if (values.size() > 1) {
                    report.add(Kind.CHANGED, where + " (" + type.yamlType + ")", "Its targets retried "
                            + values.stream().map(String::valueOf).collect(Collectors.joining(", ")) + " times; the"
                            + " gateway sets retries per provider, so the largest is used.");
                }
            });
        }

        String tw = "request_timeout";
        Set<Long> allTimeouts = new LinkedHashSet<>();
        timeouts.values().forEach(allTimeouts::addAll);
        if (allTimeouts.equals(java.util.Collections.singleton(null))) {
            return;
        }
        if (allTimeouts.size() == 1) {
            property(tw, RESILIENCE + "timeout.chat-timeout-ms", String.valueOf(allTimeouts.iterator().next()));
        } else {
            timeouts.forEach((type, values) -> {
                Long max = values.stream().filter(Objects::nonNull).max(Long::compare).orElse(null);
                if (max == null) {
                    report.add(Kind.CHANGED, tw + " (" + type.yamlType + ")", "No request_timeout in Portkey; the"
                            + " gateway's default of 30 seconds applies.");
                    return;
                }
                property(tw + " (" + type.yamlType + ")", RESILIENCE + "providers." + type.yamlType
                        + ".timeout.chat-timeout-ms", String.valueOf(max));
                if (values.size() > 1) {
                    report.add(Kind.CHANGED, tw + " (" + type.yamlType + ")", "Its targets had different timeouts;"
                            + " the gateway sets one per provider, so the longest is used.");
                }
            });
        }
        report.add(Kind.CHANGED, tw, "Applied to non-streaming calls. A streaming call keeps the gateway's streaming"
                + " timeout (dvara.llm-gateway.resilience.timeout.streaming-timeout-ms, 120 seconds by default).");
    }

    private void property(String where, String key, String value) {
        out.properties.put(key, value);
        report.add(Kind.MAPPED, where, "`" + key + "=" + value + "` in dvara-import.properties.");
    }

    // ------------------------------------------------------------------------------------------------
    // Workspace and policies
    // ------------------------------------------------------------------------------------------------

    private void buildWorkspaceAndPolicies() {
        out.workspaces.add(new Workspace(DEFAULT_WORKSPACE, null, new LinkedHashMap<>(workspaceMetadata)));
        if (workspaceMetadata.isEmpty()) {
            report.add(Kind.ADDED, "workspaces", "Workspace `default`. Its PII and prompt-injection detections are"
                    + " logged and the request goes through; pii.action: REDACT and guardrail.action: BLOCK make"
                    + " them strict (commented in gateway.yaml).");
        }

        int n = 1;
        for (ModelCheck c : modelChecks) {
            String id = "portkey-guardrail-models" + (modelChecks.size() > 1 ? "-" + n++ : "");
            Set<String> models = new TreeSet<>(c.models());
            if (c.deny()) {
                out.policies.add(new Policy(id, null, modelListDsl(models, "denylist",
                        "This model is not allowed by this gateway")));
                report.add(Kind.MAPPED, c.where(), "Policy `" + id + "` refuses " + backticked(models) + " with 403.");
            } else {
                // A policy checks the model the caller names and the model the route sends, so a route whose
                // provider is sent a listed model is allowed by its own name too.
                for (RouteDraft r : routesByModel.values()) {
                    if (!r.model().equals(CATCH_ALL) && models.contains(r.sentModel())) {
                        models.add(r.model());
                    }
                }
                out.policies.add(new Policy(id, null, modelListDsl(models, "allowlist",
                        "This model is not allowed by this gateway")));
                report.add(Kind.MAPPED, c.where(), "Policy `" + id + "` allows only " + backticked(models)
                        + " and refuses any other model with 403.");
                report.add(Kind.CHANGED, c.where(), "The policy checks both the model a caller names and the model the"
                        + " route sends, so a caller must name an allowed model or a route that sends one.");
            }
        }

        boolean catchAll = routesByModel.containsKey(CATCH_ALL);
        Set<String> served = new TreeSet<>();
        for (RouteDraft r : routesByModel.values()) {
            served.add(r.model());
            served.add(r.sentModel());
            r.fallbacks().stream().map(Fallback::model).filter(Objects::nonNull).forEach(served::add);
        }
        if (!catchAll && !served.isEmpty()) {
            out.policies.add(new Policy("portkey-served-models", null, modelListDsl(served, "allowlist",
                    "This model is not served by this gateway")));
            report.add(Kind.MAPPED, "routes", "Only the models these configs route are served: policy"
                    + " `portkey-served-models` refuses any other model with 403. Without it, a request for another"
                    + " model would go to whichever configured provider serves it.");
        }
    }

    private static String backticked(Collection<String> names) {
        return names.stream().map(x -> "`" + x + "`").collect(Collectors.joining(", "));
    }

    static String modelListDsl(Set<String> models, String list, String message) {
        StringBuilder dsl = new StringBuilder();
        dsl.append("version: \"1\"\n");
        dsl.append("rules:\n");
        dsl.append("  - id: ").append(list.equals("allowlist") ? "only-listed-models" : "no-listed-models").append('\n');
        dsl.append("    conditions:\n");
        dsl.append("      model:\n");
        dsl.append("        ").append(list).append(":\n");
        for (String m : models) {
            dsl.append("          - ").append(GatewayYamlWriter.quote(m)).append('\n');
        }
        dsl.append("    action: DENY\n");
        dsl.append("    deny_message: ").append(GatewayYamlWriter.quote(message)).append('\n');
        return dsl.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // The closing lines
    // ------------------------------------------------------------------------------------------------

    private void finalActions() {
        report.add(Kind.ACTION, "api_keys", "Mint the gateway's API keys. gateway.yaml has none, so the gateway"
                + " starts and refuses every call until you add one: `java -jar dvara-gateway-server-<version>-app.jar"
                + " --generate-key --name <name> --workspace " + DEFAULT_WORKSPACE + "` prints the key once and the"
                + " api_keys entry to paste.");
        report.add(Kind.ACTION, "clients", "Point clients at the gateway instead of Portkey, and stop sending Portkey's"
                + " headers: x-portkey-api-key, x-portkey-config, x-portkey-provider, x-portkey-virtual-key and the"
                + " other x-portkey-* headers. The gateway does not read them, so a config, provider, custom host,"
                + " cache setting or trace id sent that way does nothing. Send the DVARA key as"
                + " `Authorization: Bearer <key>`.");
        if (anyMetadataCondition) {
            report.add(Kind.ACTION, "clients", "x-portkey-metadata no longer chooses a target: the gateway picks a"
                    + " route by the model a request names (see the conditions above).");
        }
        report.add(Kind.NOT_MAPPED, "(Portkey cloud)", "Virtual keys and Model Catalog providers (their keys),"
                + " guardrails saved by id, prompts, budgets, rate limits, logs and any config saved only in"
                + " Portkey's app are in Portkey's cloud, not in this file, so they are not imported. Export or copy"
                + " each config you use to a JSON file and run the importer on it.");
        if (!envNames.isEmpty()) {
            report.add(Kind.ACTION, "environment", "Set these variables for the gateway, which gateway.yaml reads"
                    + " at startup: " + String.join(", ", envNames) + ".");
        }
        if (!out.properties.isEmpty()) {
            report.add(Kind.ACTION, "dvara-import.properties", "Start the gateway with"
                    + " `--spring.config.additional-location=file:dvara-import.properties`, or set the same"
                    + " properties another way.");
        }
        report.add(Kind.ADDED, "", "Every request carries an API key that resolves to a workspace; gateway.yaml holds"
                + " only each key's SHA-256 fingerprint.");
        report.add(Kind.ADDED, "", "PII detection and a prompt-injection guardrail on every request, set per"
                + " workspace, run inside the gateway: no call to a guardrail vendor.");
        report.add(Kind.ADDED, "", "A policy engine: model allow and deny lists, max_tokens and tool rules, checked"
                + " on the model the route actually sends.");
        report.add(Kind.ADDED, "", "A tamper-evident audit log, each record chained to the last with an HMAC, when"
                + " DVARA_AUDIT_FILE_PATH and DVARA_AUDIT_HMAC_SECRET are set.");
        report.add(Kind.ADDED, "", "A context-window check before each request is sent, a circuit breaker per"
                + " provider, and Prometheus metrics and OpenTelemetry traces.");
        report.add(Kind.ADDED, "", "Provider keys are read from the gateway's own environment, on servers you run.");
    }

    // ------------------------------------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------------------------------------

    /** The report's name for a part of the current config. */
    private String where(String path) {
        if (multi) {
            return path.isEmpty() ? currentId : currentId + "." + path;
        }
        return path.isEmpty() ? "(config)" : path;
    }

    /**
     * A target with its keys in snake_case, as Portkey reads both ({@code beforeRequestHooks} and
     * {@code before_request_hooks}). The old form, {@code mode} and {@code options}, becomes
     * {@code strategy.mode} and {@code targets}.
     */
    private Map<String, Object> node(Map<String, Object> raw, String path) {
        Map<String, Object> n = new LinkedHashMap<>();
        raw.forEach((k, v) -> n.put(snake(k), v));
        if (n.containsKey("mode") && !n.containsKey("strategy") && n.get("mode") instanceof String mode) {
            n.remove("mode");
            Map<String, Object> strategy = new LinkedHashMap<>();
            strategy.put("mode", mode);
            n.put("strategy", strategy);
            if (n.containsKey("options") && !n.containsKey("targets")) {
                n.put("targets", n.remove("options"));
            }
            report.add(Kind.MAPPED, where(path), "The older form (mode, options) is read as strategy.mode and targets.");
        }
        if (n.get("strategy") instanceof Map<?, ?> s) {
            Map<String, Object> strategy = new LinkedHashMap<>();
            stringKeys(s).forEach((k, v) -> strategy.put(snake(k), v));
            n.put("strategy", strategy);
        }
        if (n.get("retry") instanceof Map<?, ?> r) {
            Map<String, Object> retry = new LinkedHashMap<>();
            stringKeys(r).forEach((k, v) -> retry.put(snake(k), v));
            n.put("retry", retry);
        }
        return n;
    }

    static String snake(String key) {
        StringBuilder b = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) {
                b.append('_').append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** A config's name in the output, from its file name or slug. */
    static String idOf(String name) {
        String n = name.replaceAll("(?i)\\.json$", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-").replaceAll("(^[-.]+|[-.]+$)", "");
        return n.isEmpty() ? "config" : n;
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? stringKeys(m) : null;
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Integer integer(Object value) {
        Double d = number(value);
        if (d == null || d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
            return null;
        }
        return d.intValue();
    }
}
