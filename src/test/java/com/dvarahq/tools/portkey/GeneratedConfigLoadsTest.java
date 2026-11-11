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

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlLoader;
import com.dvarahq.autoconfigure.config.yaml.YamlProviderType;
import com.dvarahq.autoconfigure.config.yaml.repo.YamlConfigStore;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.Policy;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RequestContext;
import com.dvarahq.core.routing.Route;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.WeightedRoutingStrategy;
import com.dvarahq.policy.PolicyCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The output is only worth something if the gateway accepts it. Each fixture is imported through the command
 * line, and what it writes is read back with the open gateway's own code: its file loader and validator, its
 * configuration store, its policy compiler, its routing engine and Spring's property binder.
 */
class GeneratedConfigLoadsTest {

    static Stream<String> fixtures() {
        return Fixtures.ALL.stream();
    }

    /** Runs the command line on a fixture and returns the folder it wrote. */
    private static Path importToDisk(String fixture, Path dir) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {Fixtures.path(fixture).toString(), "-o", dir.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertThat(code).as("exit code; stderr: %s", err).isEqualTo(Main.OK);
        return dir;
    }

    /** Runs the command line on a config given as text and returns the folder it wrote. */
    private static Path importText(Path dir, String json) throws IOException {
        Path source = dir.resolve("config.json");
        Files.writeString(source, json);
        Path out = dir.resolve("out");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(Main.run(new String[] {source.toString(), "-o", out.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err)))
                .as("exit code; stderr: %s", err).isEqualTo(Main.OK);
        return out;
    }

    /** An environment where every variable gateway.yaml names is set, as an operator would set them. */
    private static Function<String, String> environment(Path dir) {
        return name -> switch (name) {
            case "DVARA_CONFIG_FILE" -> dir.resolve(Main.GATEWAY_YAML).toString();
            case "AWS_REGION" -> "us-east-1";
            default -> name.endsWith("_BASE") || name.endsWith("_URL") ? "https://example.test" : "value-of-" + name;
        };
    }

    private static GatewayYamlConfig load(Path dir) {
        return GatewayYamlLoader.load(environment(dir)).orElseThrow();
    }

    private static GatewayProperties bindProperties(Path dir) throws IOException {
        Properties file = new Properties();
        file.load(new StringReader(Files.readString(dir.resolve(Main.PROPERTIES))));
        Map<String, String> map = new HashMap<>();
        file.forEach((k, v) -> map.put((String) k, (String) v));
        return new Binder(new MapConfigurationPropertySource(map)).bind("dvara.llm-gateway", GatewayProperties.class)
                .get();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void theGatewaysLoaderReadsTheFileAndFindsNothingWrong(String fixture, @TempDir Path dir) {
        importToDisk(fixture, dir);
        GatewayYamlConfig config = load(dir);

        assertThat(GatewayYamlLoader.validate(config)).as("gateway.yaml validation errors").isEmpty();
        // The store refuses at construction what the loader lets through (a bad key entry, say).
        YamlConfigStore store = new YamlConfigStore(config);
        assertThat(store.routes()).hasSize(Fixtures.importFixture(fixture).config().routes.size());
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyProviderBecomesThePropertiesItsBeanRegistersFrom(String fixture, @TempDir Path dir) {
        importToDisk(fixture, dir);
        GatewayYamlConfig config = load(dir);
        if (config.getProviders() == null) {
            return;
        }
        for (GatewayYamlConfig.ProviderEntry entry : config.getProviders()) {
            YamlProviderType type = YamlProviderType.of(entry.getType()).orElseThrow();
            Map<String, Object> props = new HashMap<>();
            type.mapTo(entry, props);
            String switchKey = type.propertyPrefix() + (type.switchedOn() == YamlProviderType.Switch.API_KEY
                    ? ".api-key" : ".enabled");
            assertThat(props).as("properties for %s", entry.getType()).containsKey(switchKey);
            assertThat(String.valueOf(props.get(switchKey))).isNotBlank();
            if (type.baseUrlRequiredToRegister()) {
                assertThat(props).as("Azure needs its base URL to register").containsKey(type.propertyPrefix() + ".base-url");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyRouteNamesConfiguredProvidersAndAChainTheGatewayAccepts(String fixture, @TempDir Path dir) {
        importToDisk(fixture, dir);
        GatewayYamlConfig config = load(dir);
        Set<String> providers = config.getProviders() == null ? Set.of()
                : config.getProviders().stream().map(GatewayYamlConfig.ProviderEntry::getType).collect(Collectors.toSet());
        for (Route route : new YamlConfigStore(config).routes()) {
            RouteConfig rc = routeConfig(route);
            assertThat(rc.invalidPin()).as("pin of %s", route.getId()).isNull();
            assertThat(rc.invalidFallbacks()).as("fallbacks of %s", route.getId()).isNull();
            route.getProviders().forEach(p -> assertThat(providers).as("route %s", route.getId()).contains(p.getProvider()));
            rc.getFallbacks().forEach(f -> assertThat(providers).as("fallback of %s", route.getId()).contains(f.provider()));
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyPolicyCompilesWithTheGatewaysPolicyCompiler(String fixture, @TempDir Path dir) {
        importToDisk(fixture, dir);
        YamlConfigStore store = new YamlConfigStore(load(dir));
        PolicyCompiler compiler = new PolicyCompiler();
        for (Policy policy : store.policies()) {
            assertThat(compiler.compile(policy).rules()).as("rules of %s", policy.getId()).isNotEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void everyPropertiesFileBindsToTheGatewaysProperties(String fixture, @TempDir Path dir) throws IOException {
        importToDisk(fixture, dir);
        if (Files.exists(dir.resolve(Main.PROPERTIES))) {
            assertThat(bindProperties(dir).getResilience()).isNotNull();
        }
    }

    @Test
    void conditionsOnTheModelNameRouteThoseNamesAndEverythingElseGoesToTheDefault(@TempDir Path dir) {
        importToDisk("model-aliases.json", dir);
        RoutingEngine engine = engine(new YamlConfigStore(load(dir)).routes());
        List<LlmProvider> providers = List.of(provider("openai"), provider("anthropic"));

        RequestContext fast = RequestContext.builder().build();
        assertThat(engine.route(ChatRequest.builder().model("fastest").build(), providers, fast).name())
                .isEqualTo("openai");
        assertThat(fast.getResolvedModel()).isEqualTo("gpt-4o-mini");

        RequestContext smart = RequestContext.builder().build();
        assertThat(engine.route(ChatRequest.builder().model("smartest").build(), providers, smart).name())
                .isEqualTo("anthropic");
        assertThat(smart.getResolvedModel()).isEqualTo("claude-3.5-sonnet");

        RequestContext other = RequestContext.builder().build();
        assertThat(engine.route(ChatRequest.builder().model("anything").build(), providers, other).name())
                .isEqualTo("openai");
        assertThat(other.getResolvedModel()).isEqualTo("gpt-4o");
    }

    @Test
    void aFallbackChainIsTheRoutesChainInTheGateway(@TempDir Path dir) {
        importToDisk("fallback.json", dir);
        RoutingEngine engine = engine(new YamlConfigStore(load(dir)).routes());
        RequestContext ctx = RequestContext.builder().build();
        LlmProvider chosen = engine.route(ChatRequest.builder().model("gpt-4o").build(),
                List.of(provider("anthropic"), provider("openai")), ctx);
        assertThat(chosen.name()).isEqualTo("openai");
        assertThat(ctx.getMatchedRoute().getFallbacks())
                .containsExactly(new RouteConfig.FallbackTarget("anthropic", "claude-sonnet-4-20250514"));
    }

    @Test
    void aBackupWithNoModelIsAcceptedByTheGateway(@TempDir Path dir) throws IOException {
        Path out = importText(dir, """
                {"strategy": {"mode": "fallback"}, "targets": [
                  {"provider": "openai", "api_key": "${A}"}, {"provider": "anthropic", "api_key": "${B}"}]}""");
        GatewayYamlConfig config = load(out);
        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
        Route route = new YamlConfigStore(config).routes().getFirst();
        RouteConfig rc = routeConfig(route);
        assertThat(rc.invalidFallbacks()).isNull();
        assertThat(rc.getFallbacks()).containsExactly(new RouteConfig.FallbackTarget("anthropic", null));
    }

    @Test
    void theTimeoutBindsToTheGatewaysChatTimeout(@TempDir Path dir) throws IOException {
        importToDisk("loadbalance.json", dir);
        assertThat(bindProperties(dir).getResilience().getTimeout().getChatTimeoutMs()).isEqualTo(30_000);
    }

    @Test
    void perProviderRetriesAndTimeoutsBindToTheGatewaysProviderOverrides(@TempDir Path dir) throws IOException {
        Path out = importText(dir, """
                {"strategy": {"mode": "fallback"}, "targets": [
                  {"provider": "openai", "api_key": "${A}", "retry": {"attempts": 3}, "override_params": {"model": "gpt-4o"}},
                  {"provider": "azure-openai", "api_key": "${B}", "resource_name": "acme", "deployment_id": "gpt-4o",
                   "request_timeout": 15000}]}""");
        GatewayProperties.Resilience r = bindProperties(out).getResilience();
        assertThat(r.getProviders().get("openai").getRetry().getMaxAttempts()).isEqualTo(4);
        assertThat(r.getProviders().get("azure-openai").getRetry().getMaxAttempts()).isEqualTo(1);
        assertThat(r.getProviders().get("azure-openai").getTimeout().getChatTimeoutMs()).isEqualTo(15_000);
    }

    @Test
    void theGlobalRetryBindsToTheGatewaysRetry(@TempDir Path dir) throws IOException {
        importToDisk("admin-export.json", dir);
        assertThat(bindProperties(dir).getResilience().getRetry().getMaxAttempts()).isEqualTo(6);
    }

    @Test
    void aServerWithCredentialsInItsUrlIsLoadedAtItsOwnHostAndTheCredentialsAreNowhere(@TempDir Path dir)
            throws IOException {
        importToDisk("self-hosted.json", dir);
        GatewayYamlConfig config = load(dir);
        assertThat(config.getProviders()).anySatisfy(p -> {
            assertThat(p.getType()).isEqualTo("openai");
            assertThat(p.getBaseUrl()).isEqualTo("https://vllm.internal:8000/v1");
        });
        for (String file : List.of(Main.GATEWAY_YAML, Main.REPORT)) {
            assertThat(Files.readString(dir.resolve(file))).as(file).doesNotContain("LITERAL-HOST-SECRET");
        }
        RoutingEngine engine = engine(new YamlConfigStore(config).routes());
        RequestContext ctx = RequestContext.builder().build();
        LlmProvider chosen = engine.route(ChatRequest.builder().model("whatever").build(),
                List.of(provider("ollama"), provider("openai")), ctx);
        assertThat(chosen.name()).isEqualTo("openai");
        assertThat(ctx.getResolvedModel()).isEqualTo("meta-llama/Llama-3.1-8B-Instruct");
    }

    @Test
    void severalConfigsGetNamedRoutesAndAnAllowlistTheGatewayCompiles(@TempDir Path dir) throws IOException {
        Path in = Files.createDirectory(dir.resolve("configs"));
        Files.writeString(in.resolve("chat.json"), Fixtures.read("quickstart.json"));
        Files.writeString(in.resolve("support.json"), Fixtures.read("guardrails.json"));
        Path out = dir.resolve("out");
        assertThat(Main.run(new String[] {in.toString(), "-o", out.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())))
                .isEqualTo(Main.OK);
        GatewayYamlConfig config = load(out);
        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
        YamlConfigStore store = new YamlConfigStore(config);
        assertThat(store.routes()).extracting(Route::getModelPattern).containsExactly("chat", "support");
        assertThat(store.policies()).extracting(Policy::getId)
                .containsExactlyInAnyOrder("portkey-guardrail-models", "portkey-served-models");
        PolicyCompiler compiler = new PolicyCompiler();
        store.policies().forEach(p -> assertThat(compiler.compile(p).rules()).isNotEmpty());

        RoutingEngine engine = engine(store.routes());
        RequestContext ctx = RequestContext.builder().build();
        engine.route(ChatRequest.builder().model("support").build(), List.of(provider("openai"), provider("anthropic")), ctx);
        assertThat(ctx.getResolvedModel()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    void theWorkspaceGuardrailSettingsAreReadByTheGateway(@TempDir Path dir) {
        importToDisk("guardrails.json", dir);
        GatewayYamlConfig config = load(dir);
        assertThat(config.getWorkspaces()).singleElement().satisfies(w -> assertThat(w.getMetadata())
                .containsEntry("pii.action", "REDACT").containsEntry("guardrail.action", "BLOCK"));
        assertThat(com.dvarahq.core.pii.PiiAction.valueOf("REDACT")).isNotNull();
        assertThat(com.dvarahq.core.guardrail.GuardrailAction.valueOf("BLOCK")).isNotNull();
    }

    @Test
    void aFileWithNoPropertiesToCarryWritesNoPropertiesFile(@TempDir Path dir) {
        importToDisk("guardrails.json", dir);
        assertThat(dir.resolve(Main.PROPERTIES)).doesNotExist();
    }

    // ------------------------------------------------------------------------------------------------

    /** The same conversion the gateway's route publisher makes, minus the strategies the file cannot name. */
    private static RouteConfig routeConfig(Route route) {
        return RouteConfig.builder()
                .id(route.getId())
                .modelPattern(route.getModelPattern())
                .strategy(com.dvarahq.autoconfigure.config.yaml.repo.YamlRouteRepository.toStrategy(route.getStrategy()))
                .providers(route.getProviders().stream()
                        .map(p -> RouteConfig.ProviderWeight.builder().provider(p.getProvider()).weight(p.getWeight()).build())
                        .toList())
                .pinnedModelVersion(route.getPinnedModelVersion())
                .fallbacks(route.getFallbacks() == null ? List.of() : route.getFallbacks())
                .build();
    }

    private static RoutingEngine engine(List<Route> routes) {
        List<RoutingEngine.ResolvedRoute> resolved = routes.stream().map(r -> {
            RouteConfig rc = routeConfig(r);
            assertThat(rc.getStrategy()).isEqualTo(RouteConfig.Strategy.WEIGHTED);
            return new RoutingEngine.ResolvedRoute(rc, new WeightedRoutingStrategy(rc.getProviders()));
        }).toList();
        return new RoutingEngine(new ModelPrefixRoutingStrategy(), resolved);
    }

    /** A provider that only has a name; routing chooses by name, and nothing here calls it. */
    private static LlmProvider provider(String name) {
        return (LlmProvider) Proxy.newProxyInstance(LlmProvider.class.getClassLoader(),
                new Class<?>[] {LlmProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "name", "toString" -> name;
                    case "supports" -> false;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
