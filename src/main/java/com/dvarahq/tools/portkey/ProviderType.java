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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The open gateway's provider types, and the Portkey provider ids that map onto each.
 *
 * <p>The gateway names some models with a prefix of its own ({@code azure/}, {@code bedrock/},
 * {@code groq/}, {@code ollama/}) and others bare ({@code gpt-4o}, {@code claude-sonnet-4-5}). Portkey sends
 * the model as written. {@link #modelPrefix} is what the gateway adds.
 */
enum ProviderType {
    OPENAI("openai", "", "OPENAI_API_KEY", true),
    AZURE_OPENAI("azure-openai", "azure/", "AZURE_OPENAI_API_KEY", true),
    ANTHROPIC("anthropic", "", "ANTHROPIC_API_KEY", true),
    GEMINI("gemini", "", "GEMINI_API_KEY", true),
    MISTRAL("mistral", "", "MISTRAL_API_KEY", true),
    COHERE("cohere", "", "COHERE_API_KEY", true),
    GROQ("groq", "groq/", "GROQ_API_KEY", true),
    QWEN("qwen", "", "DASHSCOPE_API_KEY", true),
    DEEPSEEK("deepseek", "", "DEEPSEEK_API_KEY", true),
    MOONSHOT("moonshot", "", "MOONSHOT_API_KEY", true),
    CHATGLM("chatglm", "", "ZHIPU_API_KEY", true),
    GROK("grok", "", "XAI_API_KEY", true),
    OLLAMA("ollama", "ollama/", null, false),
    BEDROCK("bedrock", "bedrock/", null, false);

    /** The value of {@code type:} in gateway.yaml, which is also the name a route uses. */
    final String yamlType;
    /** The prefix the gateway puts on this provider's model names. */
    final String modelPrefix;
    /** The variable the output reads this provider's key from. */
    final String keyEnv;
    /** Whether the provider takes an API key. Ollama and Bedrock do not. */
    final boolean takesApiKey;

    ProviderType(String yamlType, String modelPrefix, String keyEnv, boolean takesApiKey) {
        this.yamlType = yamlType;
        this.modelPrefix = modelPrefix;
        this.keyEnv = keyEnv;
        this.takesApiKey = takesApiKey;
    }

    /** Portkey provider ids (src/globals.ts) the gateway has a provider for. */
    private static final Map<String, ProviderType> BY_PORTKEY_ID = Map.ofEntries(
            Map.entry("openai", OPENAI),
            Map.entry("azure-openai", AZURE_OPENAI),
            Map.entry("anthropic", ANTHROPIC),
            Map.entry("google", GEMINI),
            Map.entry("mistral-ai", MISTRAL),
            Map.entry("cohere", COHERE),
            Map.entry("groq", GROQ),
            Map.entry("dashscope", QWEN),
            Map.entry("deepseek", DEEPSEEK),
            Map.entry("moonshot", MOONSHOT),
            Map.entry("zhipu", CHATGLM),
            Map.entry("x-ai", GROK),
            Map.entry("ollama", OLLAMA),
            Map.entry("bedrock", BEDROCK));

    /** Reasons for Portkey providers the gateway lacks, where a generic "no provider" would mislead. */
    static final Map<String, String> UNSUPPORTED_REASONS = Map.of(
            "vertex-ai", "The gateway calls Gemini through Google AI Studio (Portkey's `google`), not Vertex AI.",
            "azure-ai", "The gateway's Azure provider is Azure OpenAI (`azure-openai`), not Azure AI Inference.",
            "z-ai", "The gateway's GLM provider calls Zhipu's open.bigmodel.cn (Portkey's `zhipu`), not api.z.ai.",
            "palm", "PaLM is retired; the gateway calls Gemini through Google AI Studio (`google`).");

    /**
     * Words in a Model Catalog slug or virtual key name that say which provider it holds, such as
     * {@code @openai-prod} or {@code anthropic-vk-1a2b}. Longest first, so {@code azure-openai} wins over
     * {@code openai}. A guess: the report asks the operator to check it.
     */
    private static final List<Map.Entry<String, ProviderType>> NAME_HINTS = List.of(
            Map.entry("azure-openai", AZURE_OPENAI),
            Map.entry("mistral-ai", MISTRAL),
            Map.entry("anthropic", ANTHROPIC),
            Map.entry("dashscope", QWEN),
            Map.entry("deepseek", DEEPSEEK),
            Map.entry("moonshot", MOONSHOT),
            Map.entry("bedrock", BEDROCK),
            Map.entry("mistral", MISTRAL),
            Map.entry("gemini", GEMINI),
            Map.entry("google", GEMINI),
            Map.entry("claude", ANTHROPIC),
            Map.entry("openai", OPENAI),
            Map.entry("cohere", COHERE),
            Map.entry("ollama", OLLAMA),
            Map.entry("azure", AZURE_OPENAI),
            Map.entry("zhipu", CHATGLM),
            Map.entry("groq", GROQ),
            Map.entry("qwen", QWEN),
            Map.entry("grok", GROK),
            Map.entry("xai", GROK),
            Map.entry("oai", OPENAI),
            Map.entry("aws", BEDROCK));

    static Optional<ProviderType> forPortkeyId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_PORTKEY_ID.get(id.strip().toLowerCase(Locale.ROOT)));
    }

    /** The provider a slug or virtual key name suggests, by the first word in it that names one. */
    static Optional<ProviderType> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String n = name.toLowerCase(Locale.ROOT).replace("_", "-");
        if (n.startsWith("@")) {
            n = n.substring(1);
        }
        for (var hint : NAME_HINTS) {
            String w = hint.getKey();
            if (n.equals(w) || n.startsWith(w + "-") || n.startsWith(w + ".") || n.contains("-" + w + "-")
                    || n.endsWith("-" + w)) {
                return Optional.of(hint.getValue());
            }
        }
        return Optional.empty();
    }

    /** The provider a model name says serves it. Only the families whose name says so. */
    static Optional<ProviderType> fromModel(String model) {
        if (model == null) {
            return Optional.empty();
        }
        String m = model.toLowerCase(Locale.ROOT);
        if (m.startsWith("gpt-") || m.startsWith("chatgpt-") || m.startsWith("o1") || m.startsWith("o3")
                || m.startsWith("o4")) {
            return Optional.of(OPENAI);
        }
        if (m.startsWith("claude-")) {
            return Optional.of(ANTHROPIC);
        }
        if (m.startsWith("gemini-")) {
            return Optional.of(GEMINI);
        }
        if (m.startsWith("command")) {
            return Optional.of(COHERE);
        }
        return Optional.empty();
    }

    /** The model name the gateway uses for a model Portkey sends to this provider. */
    String dvaraModel(String portkeyModel) {
        return portkeyModel.startsWith(modelPrefix) ? portkeyModel : modelPrefix + portkeyModel;
    }
}
