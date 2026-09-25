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
     * only when explicitly chosen <em>and</em> its key is set:
     * {@code AGENTIC_LLM=claude} with {@code ANTHROPIC_API_KEY} (optional
     * {@code ANTHROPIC_MODEL}, {@code ANTHROPIC_URL}), or
     * {@code AGENTIC_LLM=openai} with {@code OPENAI_API_KEY}. Hosted models are
     * always wrapped so failures fall back to the offline provider.
     *
     * @param env environment variables
     * @return the provider
     */
    public static LlmProvider fromEnvironment(Map<String, String> env) {
        DeterministicLlmProvider offline = new DeterministicLlmProvider();
        String choice = env.getOrDefault("AGENTIC_LLM", "");
        String anthropicKey = env.get("ANTHROPIC_API_KEY");
        if ("claude".equalsIgnoreCase(choice) && anthropicKey != null && !anthropicKey.isBlank()) {
            String model = env.getOrDefault("ANTHROPIC_MODEL", "claude-sonnet-5");
            URI endpoint = URI.create(env.getOrDefault("ANTHROPIC_URL", "https://api.anthropic.com/v1/messages"));
            return new FailClosedLlmProvider(new AnthropicLlmProvider(anthropicKey, model, endpoint, 8000), offline);
        }
        String key = env.get("OPENAI_API_KEY");
        if ("openai".equalsIgnoreCase(choice) && key != null && !key.isBlank()) {
            String model = env.getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
            URI endpoint = URI.create(env.getOrDefault("OPENAI_URL", "https://api.openai.com/v1/chat/completions"));
            return new FailClosedLlmProvider(new OpenAiLlmProvider(key, model, endpoint), offline);
        }
        return offline;
    }
}
