package com.agentsdlc.orchestrator.llm;

import java.net.URI;
import java.util.Map;

/** Chooses the LLM provider from the environment, defaulting to fully offline. */
public final class LlmProviders {

    private LlmProviders() {
    }

    /**
     * Resolves the provider from the process environment.
     *
     * @return the configured provider
     */
    public static LlmProvider fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Resolves the provider from the given variables. A hosted model is used
     * only when {@code AGENTIC_LLM=openai} <em>and</em> {@code OPENAI_API_KEY}
     * is set; it is always wrapped so failures fall back to the offline
     * provider.
     *
     * @param env environment variables
     * @return the provider
     */
    public static LlmProvider fromEnvironment(Map<String, String> env) {
        DeterministicLlmProvider offline = new DeterministicLlmProvider();
        String key = env.get("OPENAI_API_KEY");
        if ("openai".equalsIgnoreCase(env.get("AGENTIC_LLM")) && key != null && !key.isBlank()) {
            String model = env.getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
            URI endpoint = URI.create(env.getOrDefault("OPENAI_URL", "https://api.openai.com/v1/chat/completions"));
            return new FailClosedLlmProvider(new OpenAiLlmProvider(key, model, endpoint), offline);
        }
        return offline;
    }
}
