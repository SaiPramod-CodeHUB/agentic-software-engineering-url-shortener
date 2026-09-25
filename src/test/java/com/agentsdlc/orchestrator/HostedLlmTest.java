package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentsdlc.orchestrator.llm.AnthropicLlmProvider;
import com.agentsdlc.orchestrator.llm.FailClosedLlmProvider;
import com.agentsdlc.orchestrator.llm.LlmOutput;
import com.agentsdlc.orchestrator.llm.LlmPrompts;
import com.agentsdlc.orchestrator.llm.LlmProvider;
import com.agentsdlc.orchestrator.llm.LlmProviders;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Claude provider against a local fake Messages API: no network, no key. */
class HostedLlmTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI serve(int status, String body, AtomicReference<String> seenBody, Map<String, String> seenHeaders)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenHeaders.put("x-api-key", exchange.getRequestHeaders().getFirst("x-api-key"));
            seenHeaders.put("anthropic-version", exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
    }

    @Test
    void claudeProviderSendsTheMessagesApiShapeAndReadsTextBlocks() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        Map<String, String> headers = new ConcurrentHashMap<>();
        URI url = serve(200, "{\"content\":[{\"type\":\"text\",\"text\":\"hello \"},{\"type\":\"text\",\"text\":\"world\"}]}",
                body, headers);
        LlmProvider claude = new AnthropicLlmProvider("test-key", "claude-sonnet-5", url, 100);

        String answer = claude.complete(new LlmRequest("normalize", "Normalise.", "make links smarter", Map.of()));

        assertThat(answer).isEqualTo("hello world");
        assertThat(headers).containsEntry("x-api-key", "test-key")
                .containsEntry("anthropic-version", AnthropicLlmProvider.API_VERSION);
        JsonNode sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(sent.path("system").asText()).contains("Return ONLY one JSON object");
        assertThat(sent.path("messages").path(0).path("content").asText()).contains("make links smarter");
    }

    @Test
    void httpErrorsFailClosedToTheOfflineProvider() throws Exception {
        URI url = serve(401, "{\"error\":\"invalid key\"}", new AtomicReference<>(), new HashMap<>());
        AnthropicLlmProvider claude = new AnthropicLlmProvider("bad", "claude-sonnet-5", url, 100);
        LlmRequest triage = new LlmRequest("triage", "", "alias overwritten, wrong destination", Map.of());
        assertThatThrownBy(() -> claude.complete(triage)).hasMessageContaining("HTTP 401");

        LlmProvider provider = LlmProviders.fromEnvironment(Map.of("AGENTIC_LLM", "claude", "ANTHROPIC_API_KEY", "bad",
                "ANTHROPIC_URL", url.toString()));
        assertThat(provider).isInstanceOf(FailClosedLlmProvider.class);
        assertThat(provider.name()).startsWith("anthropic:claude-sonnet-5");
        assertThat(provider.complete(triage)).contains("alias-registration");
        assertThat(((FailClosedLlmProvider) provider).fallbackCount()).isEqualTo(1);
    }

    @Test
    void claudeIsOnlyUsedWhenChosenAndKeyed() {
        assertThat(LlmProviders.fromEnvironment(Map.of("ANTHROPIC_API_KEY", "k")).name())
                .isEqualTo("deterministic-offline");
        assertThat(LlmProviders.fromEnvironment(Map.of("AGENTIC_LLM", "claude")).name())
                .isEqualTo("deterministic-offline");
    }

    @Test
    void outputCleanupAndPromptContracts() {
        assertThat(LlmOutput.stripFences("Here you go:\n```java\nclass A {}\n```\nthanks")).isEqualTo("class A {}");
        assertThat(LlmOutput.stripFences("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(LlmPrompts.packageFor("custom-domains")).isEqualTo("generated.customdomains");
        assertThat(LlmPrompts.packageFor("2fa")).isEqualTo("generated.feature2fa");
        LlmRequest main = new LlmRequest("code", "Implement.", "spec", Map.of("feature", "slug-rules",
                "template", "slug-rules/main"));
        assertThat(LlmPrompts.system(main)).contains("package generated.slugrules");
        assertThat(LlmPrompts.user(main)).contains("feature: slug-rules").doesNotContain("template");
    }
}
