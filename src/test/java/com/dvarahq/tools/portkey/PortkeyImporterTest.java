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
import com.dvarahq.tools.portkey.DvaraConfig.Provider;
import com.dvarahq.tools.portkey.DvaraConfig.Route;
import com.dvarahq.tools.portkey.DvaraConfig.WeightedProvider;
import com.dvarahq.tools.portkey.ImportReport.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortkeyImporterTest {

    private static PortkeyImporter.Result importJson(String json) {
        return PortkeyImporter.importJson(json, "config.json");
    }

    private static Route route(PortkeyImporter.Result r, String model) {
        return r.config().routes.stream().filter(x -> x.model().equals(model)).findFirst().orElseThrow(
                () -> new AssertionError("no route for " + model + " in " + r.config().routes));
    }

    private static Provider provider(PortkeyImporter.Result r, ProviderType type) {
        Provider p = r.config().providers.get(type);
        assertThat(p).as("provider %s in %s", type, r.config().providers.keySet()).isNotNull();
        return p;
    }

    private static List<String> texts(PortkeyImporter.Result r, Kind kind) {
        return r.report().of(kind).stream().map(f -> f.where() + ": " + f.text()).toList();
    }

    private static String everything(PortkeyImporter.Result r) {
        return GatewayYamlWriter.write(r.config(), "config.json") + GatewayYamlWriter.writeProperties(r.config(), "x")
                + r.report().toMarkdown("config.json", true);
    }

    // ------------------------------------------------------------------------------------------------
    // Providers and model names
    // ------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} -> {2} {3}")
    @CsvSource({
            "openai, gpt-4o, openai, gpt-4o",
            "anthropic, claude-sonnet-4-5, anthropic, claude-sonnet-4-5",
            "google, gemini-2.5-flash, gemini, gemini-2.5-flash",
            "mistral-ai, mistral-large-latest, mistral, mistral-large-latest",
            "cohere, command-r-plus, cohere, command-r-plus",
            "groq, llama-3.3-70b-versatile, groq, groq/llama-3.3-70b-versatile",
            "dashscope, qwen-plus, qwen, qwen-plus",
            "deepseek, deepseek-chat, deepseek, deepseek-chat",
            "moonshot, kimi-k2, moonshot, kimi-k2",
            "zhipu, glm-4.6, chatglm, glm-4.6",
            "x-ai, grok-4, grok, grok-4",
            "ollama, llama3.1, ollama, ollama/llama3.1",
            "bedrock, anthropic.claude-3-5-sonnet-20240620-v1:0, bedrock, bedrock/anthropic.claude-3-5-sonnet-20240620-v1:0",
    })
    void eachSupportedProviderMapsToItsTypeAndTheModelNameTheGatewayUses(String portkey, String model, String type,
                                                                         String sent) {
        PortkeyImporter.Result r = importJson("""
                {"provider": "%s", "api_key": "${KEY}", "override_params": {"model": "%s"}}""".formatted(portkey, model));
        assertThat(r.config().providers.values()).singleElement()
                .satisfies(p -> assertThat(p.type().yamlType).isEqualTo(type));
        Route route = route(r, "*");
        assertThat(route.pinnedModel()).isEqualTo(sent);
        assertThat(route.providers()).singleElement().satisfies(p -> assertThat(p.weight()).isEqualTo(1));
    }

    @Test
    void anAzureTargetCallsItsDeploymentOnItsResource() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "azure-openai", "api_key": "${AZ}", "resource_name": "acme-eu",
                 "deployment_id": "gpt4o-prod", "api_version": "2024-08-01-preview"}""");
        assertThat(provider(r, ProviderType.AZURE_OPENAI).baseUrl().literal())
                .isEqualTo("https://acme-eu.openai.azure.com/openai");
        assertThat(route(r, "*").pinnedModel()).isEqualTo("azure/gpt4o-prod");
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("api-version 2024-10-21"));
    }

    @Test
    void anAzureTargetWhoseResourceIsInTheVaultReadsItsBaseUrlFromTheEnvironment() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "azure-openai", "virtual_key": "azure-test-4110dd", "override_params": {"model": "gpt-4o"}}""");
        Provider p = provider(r, ProviderType.AZURE_OPENAI);
        assertThat(p.baseUrl().envName()).isEqualTo("AZURE_OPENAI_BASE");
        assertThat(p.baseUrl().suffix()).isEqualTo("/openai");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("deployment `gpt-4o`"));
    }

    @ParameterizedTest
    @CsvSource({
            "vertex-ai, Vertex AI",
            "azure-ai, Azure AI Inference",
            "z-ai, open.bigmodel.cn",
            "together-ai, no provider for `together-ai`",
    })
    void aProviderTheGatewayLacksIsReportedWithTheReasonAndWritesNoRoute(String portkey, String reason) {
        PortkeyImporter.Result r = importJson("""
                {"provider": "%s", "api_key": "${K}", "override_params": {"model": "m"}}""".formatted(portkey));
        assertThat(r.config().providers).isEmpty();
        assertThat(r.config().routes).isEmpty();
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains(reason));
    }

    @Test
    void aModelWithASpaceIsNotSent() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${K}", "override_params": {"model": "gpt 4o"}}""");
        assertThat(r.config().routes).isEmpty();
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("has a space"));
    }

    // ------------------------------------------------------------------------------------------------
    // Secrets, virtual keys and Model Catalog providers
    // ------------------------------------------------------------------------------------------------

    @Test
    void aLiteralApiKeyIsNeverCopiedAndTheReportSaysToSetAndRotateIt() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "sk-proj-REAL-LOOKING-KEY-123"}""");
        assertThat(provider(r, ProviderType.OPENAI).apiKey().envName()).isEqualTo("OPENAI_API_KEY");
        assertThat(everything(r)).doesNotContain("sk-proj-REAL-LOOKING-KEY-123");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("literal").contains("rotate"));
    }

    @Test
    void aKeyWrittenAsAVariableReferenceKeepsItsVariable() {
        assertThat(provider(importJson("""
                {"provider": "openai", "api_key": "${TEAM_OPENAI_KEY}"}"""), ProviderType.OPENAI).apiKey().envName())
                .isEqualTo("TEAM_OPENAI_KEY");
        assertThat(provider(importJson("""
                {"provider": "openai", "api_key": "$TEAM_OPENAI_KEY"}"""), ProviderType.OPENAI).apiKey().envName())
                .isEqualTo("TEAM_OPENAI_KEY");
    }

    @Test
    void aVirtualKeyMapsItsProviderAndAsksForTheKeyFromTheVault() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "virtual_key": "main-258f4d", "override_params": {"model": "gpt-4o"}}""");
        assertThat(provider(r, ProviderType.OPENAI).apiKey().envName()).isEqualTo("OPENAI_API_KEY");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("Portkey's vault")
                .contains("main-258f4d").contains("OPENAI_API_KEY"));
    }

    @Test
    void aModelCatalogSlugInfersTheProviderFromItsNameAndAsksToCheckIt() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "@anthropic-backup", "override_params": {"model": "claude-sonnet-4-20250514"}}""");
        assertThat(provider(r, ProviderType.ANTHROPIC).apiKey().envName()).isEqualTo("ANTHROPIC_API_KEY");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("inferred").contains("anthropic"));
    }

    @Test
    void aSlugWithAModelInItSendsThatModel() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "@openai-vk/gpt-4.1"}""");
        assertThat(route(r, "*").pinnedModel()).isEqualTo("gpt-4.1");
    }

    @Test
    void aSlugWhoseNameSaysNothingFallsBackToTheModelAndOtherwiseIsNotServed() {
        assertThat(provider(importJson("""
                {"provider": "@private-vk", "override_params": {"model": "claude-3-opus"}}"""), ProviderType.ANTHROPIC))
                .isNotNull();
        PortkeyImporter.Result r = importJson("""
                {"provider": "@xx", "override_params": {"model": "ft://gpt4-xxxxx"}}""");
        assertThat(r.config().providers).isEmpty();
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("\"provider\""));
    }

    @Test
    void noKeyAtAllMeansCallersSentItAndTheGatewayNowHoldsIt() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "override_params": {"model": "gpt-4o"}, "retry": {"attempts": 2}}""");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("Authorization"));
    }

    @Test
    void bedrockKeysWrittenAsLiteralsAreNotCopiedAndAnAssumedRoleIsReported() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "bedrock", "aws_access_key_id": "AKIAEXAMPLE123", "aws_secret_access_key": "wJalrEXAMPLE",
                 "aws_session_token": "FwoGEXAMPLE", "aws_region": "eu-west-1",
                 "override_params": {"model": "anthropic.claude-3-haiku-20240307-v1:0"}}""");
        Provider p = provider(r, ProviderType.BEDROCK);
        assertThat(p.accessKey().envName()).isEqualTo("AWS_ACCESS_KEY_ID");
        assertThat(p.secretKey().envName()).isEqualTo("AWS_SECRET_ACCESS_KEY");
        assertThat(p.region().literal()).isEqualTo("eu-west-1");
        assertThat(everything(r)).doesNotContain("AKIAEXAMPLE123").doesNotContain("wJalrEXAMPLE")
                .doesNotContain("FwoGEXAMPLE");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("aws_session_token"));
    }

    // ------------------------------------------------------------------------------------------------
    // custom_host: never the provider's public API
    // ------------------------------------------------------------------------------------------------

    @Test
    void aCustomHostIsTheProvidersBaseUrlWithoutItsCredentials() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${VLLM_KEY}",
                 "custom_host": "https://user:pa55@vllm.internal:8000/v1?key=pa55",
                 "override_params": {"model": "meta-llama/Llama-3.1-8B-Instruct"}}""");
        assertThat(provider(r, ProviderType.OPENAI).baseUrl().literal()).isEqualTo("https://vllm.internal:8000/v1");
        assertThat(everything(r)).doesNotContain("pa55");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("user and password").contains("Rotate"));
    }

    @ParameterizedTest
    @CsvSource({"not a url", "ftp://files.internal/v1", "/just/a/path"})
    void aCustomHostThatCannotBeWrittenIsNotServedRatherThanSentToThePublicApi(String host) {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${K}", "custom_host": "%s", "override_params": {"model": "m1"}}"""
                .formatted(host));
        assertThat(r.config().providers).isEmpty();
        assertThat(r.config().routes).isEmpty();
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("Not served").contains("own API"));
    }

    @Test
    void aGroupsCustomHostAppliesToItsTargetsAsInPortkey() {
        PortkeyImporter.Result r = importJson("""
                {"custom_host": "http://llm-proxy.internal/v1", "strategy": {"mode": "single"},
                 "targets": [{"provider": "openai", "api_key": "${K}", "override_params": {"model": "m1"}}]}""");
        assertThat(provider(r, ProviderType.OPENAI).baseUrl().literal()).isEqualTo("http://llm-proxy.internal/v1");
    }

    @Test
    void aCustomHostFromAVariableStaysAVariable() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${K}", "custom_host": "${VLLM_URL}", "override_params": {"model": "m1"}}""");
        assertThat(provider(r, ProviderType.OPENAI).baseUrl().envName()).isEqualTo("VLLM_URL");
    }

    @Test
    void anthropicAndOllamaHostsLoseTheV1TheGatewayAddsItself() {
        PortkeyImporter.Result r = importJson("""
                {"strategy": {"mode": "fallback"}, "targets": [
                  {"provider": "anthropic", "api_key": "${K}", "custom_host": "https://claude-proxy.internal/v1",
                   "override_params": {"model": "claude-sonnet-4-5"}},
                  {"provider": "ollama", "custom_host": "http://ollama.internal:11434/v1", "override_params": {"model": "llama3.1"}}]}""");
        assertThat(provider(r, ProviderType.ANTHROPIC).baseUrl().literal()).isEqualTo("https://claude-proxy.internal");
        assertThat(provider(r, ProviderType.OLLAMA).baseUrl().literal()).isEqualTo("http://ollama.internal:11434");
    }

    @Test
    void aSecondEndpointOfOneProviderIsNotServedAndSaysWhy() {
        PortkeyImporter.Result r = importJson("""
                {"strategy": {"mode": "loadbalance"}, "targets": [
                  {"provider": "@openai-key-1", "override_params": {"model": "gpt-4o"}},
                  {"provider": "@openai-key-2", "override_params": {"model": "gpt-4o"}}]}""");
        assertThat(r.config().providers).hasSize(1);
        assertThat(route(r, "*").providers()).containsExactly(new WeightedProvider(ProviderType.OPENAI, 1));
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("targets[1]")
                .contains("one openai endpoint"));
    }

    // ------------------------------------------------------------------------------------------------
    // Strategies
    // ------------------------------------------------------------------------------------------------

    @Test
    void fallbackBecomesTheRoutesChainInOrder() {
        PortkeyImporter.Result r = Fixtures.importFixture("fallback.json");
        Route route = route(r, "*");
        assertThat(route.providers()).containsExactly(new WeightedProvider(ProviderType.OPENAI, 1));
        assertThat(route.pinnedModel()).isEqualTo("gpt-4o");
        assertThat(route.fallbacks()).containsExactly(new Fallback(ProviderType.ANTHROPIC, "claude-sonnet-4-20250514"));
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("on_status_codes [429, 500, 503]"));
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("default_params"))
                .anySatisfy(t -> assertThat(t).contains("drop_params"));
    }

    @Test
    void fallbackWithoutStatusCodesSaysPortkeyAlsoFailedOverOnClientErrors() {
        PortkeyImporter.Result r = Fixtures.importFixture("self-hosted.json");
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("4xx included"));
    }

    @Test
    void loadbalanceAcrossProvidersSendingTheSameModelIsAWeightedRoute() {
        PortkeyImporter.Result r = importJson("""
                {"strategy": {"mode": "loadbalance"}, "targets": [
                  {"provider": "@openai-prod", "weight": 0.7},
                  {"provider": "@anthropic-prod", "weight": 0.3}]}""");
        Route route = route(r, "*");
        assertThat(route.pinnedModel()).isNull();
        assertThat(route.providers()).containsExactly(new WeightedProvider(ProviderType.OPENAI, 70),
                new WeightedProvider(ProviderType.ANTHROPIC, 30));
    }

    @Test
    void loadbalanceAcrossDifferentModelsBecomesFailoverAndSaysSo() {
        PortkeyImporter.Result r = Fixtures.importFixture("loadbalance.json");
        Route route = route(r, "*");
        assertThat(route.pinnedModel()).isEqualTo("azure/gpt-4o-eu");
        assertThat(route.fallbacks()).containsExactly(new Fallback(ProviderType.OPENAI, "gpt-4o"),
                new Fallback(ProviderType.BEDROCK, "bedrock/anthropic.claude-3-5-sonnet-20240620-v1:0"));
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("load balancing became failover"));
    }

    @Test
    void aTargetWithWeightZeroIsNotServed() {
        PortkeyImporter.Result r = importJson("""
                {"strategy": {"mode": "loadbalance"}, "targets": [
                  {"provider": "openai", "api_key": "${A}", "weight": 1},
                  {"provider": "anthropic", "api_key": "${B}", "weight": 0}]}""");
        assertThat(r.config().providers).containsOnlyKeys(ProviderType.OPENAI);
    }

    @Test
    void singleModeCallsOnlyTheFirstTarget() {
        PortkeyImporter.Result r = importJson("""
                {"strategy": {"mode": "single"}, "targets": [
                  {"provider": "openai", "api_key": "${A}", "override_params": {"model": "gpt-4o"}},
                  {"provider": "anthropic", "api_key": "${B}"}]}""");
        assertThat(r.config().providers).containsOnlyKeys(ProviderType.OPENAI);
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("only the first target"));
    }

    @Test
    void aChainLongerThanTheGatewayAllowsIsCutAndReported() {
        StringBuilder targets = new StringBuilder();
        String[] providers = {"openai", "anthropic", "google", "mistral-ai", "cohere", "deepseek", "moonshot"};
        for (int i = 0; i < providers.length; i++) {
            targets.append(i == 0 ? "" : ",").append("""
                    {"provider": "%s", "api_key": "${K%d}", "override_params": {"model": "m%d"}}""".formatted(providers[i], i, i));
        }
        PortkeyImporter.Result r = importJson("{\"strategy\": {\"mode\": \"fallback\"}, \"targets\": [" + targets + "]}");
        assertThat(route(r, "*").fallbacks()).hasSize(PortkeyImporter.MAX_FALLBACKS);
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("at most 5"));
    }

    @Test
    void conditionsOnTheModelNameBecomeRoutesForThoseNames() {
        PortkeyImporter.Result r = Fixtures.importFixture("model-aliases.json");
        assertThat(route(r, "fastest").pinnedModel()).isEqualTo("gpt-4o-mini");
        assertThat(route(r, "smartest").pinnedModel()).isEqualTo("claude-3.5-sonnet");
        assertThat(route(r, "smartest").providers()).containsExactly(new WeightedProvider(ProviderType.ANTHROPIC, 1));
        assertThat(route(r, "*").pinnedModel()).isEqualTo("gpt-4o");
        // The catch-all is last, so it never hides a named route.
        assertThat(r.config().routes.getLast().model()).isEqualTo("*");
    }

    @Test
    void conditionsOnMetadataGiveTheirTargetARouteAndTellCallersHowToReachIt() {
        PortkeyImporter.Result r = Fixtures.importFixture("subscription-tier.json");
        assertThat(route(r, "subscription-tier-premium-model").pinnedModel()).isEqualTo("gpt-4o");
        assertThat(route(r, "*").pinnedModel()).isEqualTo("gpt-3.5-turbo");
        assertThat(r.config().routes).hasSize(2);
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("model: \"subscription-tier-premium-model\""))
                .anySatisfy(t -> assertThat(t).contains("x-portkey-metadata"));
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("is the default"));
    }

    @Test
    void aConditionalGroupInsideAnotherStrategyServesItsDefaultAndSaysSo() {
        PortkeyImporter.Result r = importJson("""
                {"strategy": {"mode": "fallback"}, "targets": [
                  {"strategy": {"mode": "conditional", "default": "b",
                    "conditions": [{"query": {"metadata.tier": "pro"}, "then": "a"}]},
                   "targets": [{"name": "a", "provider": "openai", "api_key": "${A}", "override_params": {"model": "gpt-4o"}},
                               {"name": "b", "provider": "openai", "api_key": "${A}", "override_params": {"model": "gpt-4o-mini"}}]},
                  {"provider": "anthropic", "api_key": "${B}", "override_params": {"model": "claude-haiku-4-5"}}]}""");
        assertThat(route(r, "*").pinnedModel()).isEqualTo("gpt-4o-mini");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("default target `b`"));
    }

    @Test
    void aConditionalConfigWithNoDefaultHasNoCatchAllAndAnAllowlist() {
        PortkeyImporter.Result r = importJson("""
                {"strategy": {"mode": "conditional", "conditions": [
                  {"query": {"params.model": {"$in": ["fast", "cheap"]}}, "then": "mini"}]},
                 "targets": [{"name": "mini", "provider": "openai", "api_key": "${A}", "override_params": {"model": "gpt-4o-mini"}},
                             {"name": "unused", "provider": "anthropic", "api_key": "${B}"}]}""");
        assertThat(r.config().routes).extracting(Route::model).containsExactly("fast", "cheap");
        assertThat(r.config().policies).singleElement().satisfies(p -> assertThat(p.id()).isEqualTo("portkey-served-models"));
        assertThat(r.config().providers).containsOnlyKeys(ProviderType.OPENAI);
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("never chose it"));
    }

    @Test
    void passthroughNeedsNoRoute() {
        PortkeyImporter.Result r = importJson("""
                {"passthrough": true, "retry": {"attempts": 2}}""");
        assertThat(r.config().routes).isEmpty();
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("passthrough"));
    }

    @Test
    void theOlderModeAndOptionsFormIsRead() {
        PortkeyImporter.Result r = importJson("""
                {"mode": "fallback", "options": [
                  {"provider": "openai", "api_key": "${A}", "override_params": {"model": "gpt-4o"}},
                  {"provider": "anthropic", "api_key": "${B}", "override_params": {"model": "claude-haiku-4-5"}}]}""");
        assertThat(route(r, "*").fallbacks()).containsExactly(new Fallback(ProviderType.ANTHROPIC, "claude-haiku-4-5"));
    }

    @Test
    void camelCaseKeysAreReadAsPortkeyReadsThem() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "apiKey": "${A}", "overrideParams": {"model": "gpt-4o"},
                 "beforeRequestHooks": [{"type": "guardrail", "id": "g", "deny": true,
                   "checks": [{"id": "portkey.pii", "parameters": {}}]}]}""");
        assertThat(provider(r, ProviderType.OPENAI).apiKey().envName()).isEqualTo("A");
        assertThat(route(r, "*").pinnedModel()).isEqualTo("gpt-4o");
        assertThat(r.config().workspaces.get(0).metadata()).containsEntry("pii.action", "BLOCK");
    }

    // ------------------------------------------------------------------------------------------------
    // Retries, timeouts, cache and parameters
    // ------------------------------------------------------------------------------------------------

    @Test
    void oneRetrySettingForEveryTargetIsTheGatewaysRetryCountingTheFirstCall() {
        PortkeyImporter.Result r = Fixtures.importFixture("quickstart.json");
        assertThat(r.config().properties).containsEntry("dvara.llm-gateway.resilience.retry.max-attempts", "4");
    }

    @Test
    void differentRetriesPerTargetBecomePerProviderSettings() {
        PortkeyImporter.Result r = importJson("""
                {"strategy": {"mode": "fallback"}, "targets": [
                  {"provider": "openai", "api_key": "${A}", "retry": {"attempts": 3}, "override_params": {"model": "gpt-4o"}},
                  {"provider": "anthropic", "api_key": "${B}", "request_timeout": 15000, "override_params": {"model": "claude-haiku-4-5"}}]}""");
        assertThat(r.config().properties)
                .containsEntry("dvara.llm-gateway.resilience.providers.openai.retry.max-attempts", "4")
                .containsEntry("dvara.llm-gateway.resilience.providers.anthropic.retry.max-attempts", "1")
                .containsEntry("dvara.llm-gateway.resilience.providers.anthropic.timeout.chat-timeout-ms", "15000")
                .doesNotContainKey("dvara.llm-gateway.resilience.retry.max-attempts");
    }

    @Test
    void customRetryCodesAndTheRetryAfterHeaderAreReportedAsDifferences() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${A}",
                 "retry": {"attempts": 2, "on_status_codes": [429], "use_retry_after_header": true}}""");
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("on_status_codes [429]"))
                .anySatisfy(t -> assertThat(t).contains("use_retry_after_header"));
    }

    @Test
    void noRetryIsReportedSinceTheGatewayRetriesByDefault() {
        PortkeyImporter.Result r = Fixtures.importFixture("guardrails.json");
        assertThat(r.config().properties).isEmpty();
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("max-attempts=1"));
    }

    @Test
    void cacheHasNoEquivalent() {
        PortkeyImporter.Result r = Fixtures.importFixture("quickstart.json");
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("cache (simple)"));
    }

    @Test
    void anOverriddenMaxTokensIsACapTheOperatorHasToKeepByHand() {
        PortkeyImporter.Result r = Fixtures.importFixture("fallback.json");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("max_tokens: {limit: 4000}"));
    }

    @Test
    void anUnknownKeyIsReportedNotDropped() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${A}", "some_new_field": 1}""");
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("some_new_field"));
    }

    // ------------------------------------------------------------------------------------------------
    // Guardrails: fail closed
    // ------------------------------------------------------------------------------------------------

    @Test
    void piiRedactionAndInjectionBlockingMapToTheWorkspacesOwnGuardrails() {
        PortkeyImporter.Result r = Fixtures.importFixture("guardrails.json");
        assertThat(r.config().workspaces.get(0).metadata()).containsEntry("pii.action", "REDACT")
                .containsEntry("guardrail.action", "BLOCK");
    }

    @Test
    void aModelWhitelistThatDeniesBecomesAnAllowlistPolicy() {
        PortkeyImporter.Result r = Fixtures.importFixture("guardrails.json");
        assertThat(r.config().policies).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("portkey-guardrail-models");
            assertThat(p.dsl()).contains("allowlist").contains("\"claude-haiku-4-5\"").contains("action: DENY");
        });
    }

    @Test
    void aBlockingCheckTheGatewayCannotRunIsAnActionNotASilentDrop() {
        PortkeyImporter.Result r = Fixtures.importFixture("guardrails.json");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("default.regexMatch")
                .contains("now go through"));
        // A check that only logged is reported, but asks nothing of the operator.
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("default.contains"));
        assertThat(texts(r, Kind.ACTION)).noneSatisfy(t -> assertThat(t).contains("default.contains"));
    }

    @Test
    void aGuardrailSavedByIdInPortkeysCloudIsAnAction() {
        PortkeyImporter.Result r = Fixtures.importFixture("guardrails.json");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("pg-fact-checker-xxx"));
    }

    @Test
    void piiThatBlockedBlocksAndOutputPiiScansResponses() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${A}",
                 "input_guardrails": [{"portkey.pii": {}, "deny": true}],
                 "output_guardrails": [{"patronus.pii": {}, "deny": true}]}""");
        assertThat(r.config().workspaces.get(0).metadata()).containsEntry("pii.action", "BLOCK")
                .containsEntry("pii.scan-responses", true);
    }

    @Test
    void anAsyncCheckOnlyLoggedSoItMapsToLog() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${A}",
                 "input_guardrails": [{"azure.shieldPrompt": {}, "deny": true, "async": true}]}""");
        assertThat(r.config().workspaces.get(0).metadata()).containsEntry("guardrail.action", "LOG");
    }

    @Test
    void anInvertedModelWhitelistBecomesADenylist() {
        PortkeyImporter.Result r = importJson("""
                {"provider": "openai", "api_key": "${A}",
                 "input_guardrails": [{"default.modelwhitelist": {"models": ["gpt-3.5-turbo"], "not": true}, "deny": true}]}""");
        assertThat(r.config().policies).anySatisfy(p -> assertThat(p.dsl()).contains("denylist").contains("gpt-3.5-turbo"));
    }

    // ------------------------------------------------------------------------------------------------
    // Several configs, wrappers and bad input
    // ------------------------------------------------------------------------------------------------

    @Test
    void aJsonArrayOfConfigsGivesEachARouteByNameAndAnAllowlist() {
        PortkeyImporter.Result r = PortkeyImporter.importJson("""
                [{"provider": "openai", "api_key": "${A}", "override_params": {"model": "gpt-4o"}},
                 {"provider": "anthropic", "api_key": "${B}", "override_params": {"model": "claude-haiku-4-5"}}]""",
                "team.json");
        assertThat(route(r, "team-1").pinnedModel()).isEqualTo("gpt-4o");
        assertThat(route(r, "team-2").pinnedModel()).isEqualTo("claude-haiku-4-5");
        assertThat(r.config().policies).singleElement().satisfies(p -> assertThat(p.dsl())
                .contains("\"team-1\"").contains("\"gpt-4o\"").contains("\"claude-haiku-4-5\""));
        assertThat(texts(r, Kind.CHANGED)).anySatisfy(t -> assertThat(t).contains("model: \"team-1\""));
    }

    @Test
    void amongSeveralConfigsOnlyOneThatSendsTheCallersModelCanBeServed() {
        PortkeyImporter.Result r = PortkeyImporter.importJson("""
                [{"provider": "openai", "api_key": "${A}"}, {"provider": "anthropic", "api_key": "${B}"}]""", "c.json");
        assertThat(r.config().routes).extracting(Route::model).containsExactly("*");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("override_params.model"));
        assertThat(r.config().policies).isEmpty();
    }

    @Test
    void theAdminApiWrapperIsUnwrapped() {
        PortkeyImporter.Result r = Fixtures.importFixture("admin-export.json");
        assertThat(route(r, "*").pinnedModel()).isEqualTo("gpt-4o");
        assertThat(route(r, "*").fallbacks()).containsExactly(new Fallback(ProviderType.AZURE_OPENAI, "azure/gpt-4o"));
        assertThat(r.config().properties).containsEntry("dvara.llm-gateway.resilience.retry.max-attempts", "6");
    }

    @Test
    void aConfigWithASlugTakesItsNameFromIt() {
        List<PortkeyImporter.Source> s = PortkeyImporter.sources(PortkeyImporter.parse("""
                {"slug": "pc-support-9f2", "config": "{\\"provider\\": \\"openai\\"}"}"""), "x.json");
        assertThat(s).singleElement().satisfies(x -> assertThat(x.id()).isEqualTo("pc-support-9f2"));
    }

    @Test
    void inputThatIsNotAPortkeyConfigIsRefused() {
        assertThatThrownBy(() -> importJson("{\"model_list\": []}")).isInstanceOf(PortkeyImporter.ImportException.class)
                .hasMessageContaining("not a Portkey config");
        assertThatThrownBy(() -> importJson("[]")).isInstanceOf(PortkeyImporter.ImportException.class);
        assertThatThrownBy(() -> importJson("\"x\"")).isInstanceOf(PortkeyImporter.ImportException.class);
        assertThatThrownBy(() -> importJson("{\"provider\": \"openai\",}"))
                .isInstanceOf(PortkeyImporter.ImportException.class).hasMessageContaining("line 1");
    }

    @Test
    void theReportAlwaysTellsClientsToStopSendingPortkeyHeaders() {
        PortkeyImporter.Result r = Fixtures.importFixture("quickstart.json");
        assertThat(texts(r, Kind.ACTION)).anySatisfy(t -> assertThat(t).contains("x-portkey-config")
                .contains("x-portkey-api-key"));
        assertThat(texts(r, Kind.NOT_MAPPED)).anySatisfy(t -> assertThat(t).contains("Portkey cloud"));
    }
}
