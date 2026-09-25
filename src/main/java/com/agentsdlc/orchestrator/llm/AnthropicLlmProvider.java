package com.agentsdlc.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Hosted-model provider for Claude via the Anthropic Messages API. Enabled
 * only by environment variables (see {@link LlmProviders}) and always wrapped
 * in {@link FailClosedLlmProvider}, so the default runtime stays offline.
 */
public final class AnthropicLlmProvider implements LlmProvider {

    /** API version header value. */
    public static final String API_VERSION = "2023-06-01";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final int maxTokens;

    /**
     * Creates the provider.
     *
     * @param apiKey    API key (read from the environment, never logged)
     * @param model     model name, e.g. {@code claude-sonnet-5}
     * @param endpoint  messages endpoint URL
     * @param maxTokens maximum tokens per answer
     */
    public AnthropicLlmProvider(String apiKey, String model, URI endpoint, int maxTokens) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault()).build(); // honours https.proxyHost / corporate proxies
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
        this.maxTokens = maxTokens;
    }

    @Override
    public String name() {
        return "anthropic:" + model;
    }

    @Override
    public String complete(LlmRequest request) {
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", LlmPrompts.system(request),
                    "messages", List.of(Map.of("role", "user", "content", LlmPrompts.user(request)))));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(120))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("anthropic returned HTTP " + response.statusCode(), null);
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode block : JSON.readTree(response.body()).path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            if (text.isEmpty()) {
                throw new LlmException("anthropic response had no text content", null);
            }
            return text.toString();
        } catch (IOException e) {
            throw new LlmException("anthropic call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("anthropic call interrupted", e);
        }
    }
}
