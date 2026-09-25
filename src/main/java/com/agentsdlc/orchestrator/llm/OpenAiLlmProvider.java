package com.agentsdlc.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Optional hosted-model provider using the OpenAI Chat Completions API.
 * Never used unless explicitly enabled by environment variables (see
 * {@link LlmProviders}); the default runtime makes no network calls.
 */
public final class OpenAiLlmProvider implements LlmProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String apiKey;
    private final String model;
    private final URI endpoint;

    /**
     * Creates the provider.
     *
     * @param apiKey   API key (read from the environment, never logged)
     * @param model    model name
     * @param endpoint chat-completions URL
     */
    public OpenAiLlmProvider(String apiKey, String model, URI endpoint) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
    }

    @Override
    public String name() {
        return "openai:" + model;
    }

    @Override
    public String complete(LlmRequest request) {
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system", "content", request.system()),
                            Map.of("role", "user", "content", request.prompt() + "\n\nInputs: " + request.vars()))));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("openai returned HTTP " + response.statusCode(), null);
            }
            JsonNode content = JSON.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new LlmException("openai response had no content", null);
            }
            return content.asText();
        } catch (IOException e) {
            throw new LlmException("openai call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("openai call interrupted", e);
        }
    }
}
