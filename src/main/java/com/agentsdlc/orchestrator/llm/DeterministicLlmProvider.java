package com.agentsdlc.orchestrator.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline, rule-based stand-in for a language model: same input, same
 * output, no network, no API key. It lets every scenario run in CI and makes
 * pipeline behaviour testable; the {@link LlmProvider} seam means a hosted
 * model can replace it without changing agents.
 *
 * <p>Supported purposes: {@code normalize} (JSON spec with an ambiguity
 * score), {@code triage} (JSON incident classification), {@code design} and
 * {@code docs} (Markdown), and {@code code} (Java source from
 * {@link CodeTemplates}, selected by the {@code template} variable).</p>
 */
public final class DeterministicLlmProvider implements LlmProvider {

    /** Score at or above which a request is considered ambiguous. */
    public static final double AMBIGUITY_THRESHOLD = 0.5;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VAGUE = Set.of("smarter", "better", "improve", "improved", "nicer", "faster",
            "optimize", "enhance", "modern", "cleaner", "easier", "good", "great", "intelligent");
    private static final Set<String> CONCRETE = Set.of("qr", "endpoint", "api", "ttl", "alias", "stats", "click",
            "clicks", "latency", "test", "tests", "race", "bug", "incident", "redirect", "analytics", "referrer",
            "expire", "expiry", "expired", "idle", "410", "404", "version", "payload", "duplicate", "concurrent");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    /** Creates the provider; it is stateless. */
    public DeterministicLlmProvider() {
        // Stateless.
    }

    @Override
    public String name() {
        return "deterministic-offline";
    }

    @Override
    public String complete(LlmRequest request) {
        return switch (request.purpose()) {
            case "normalize" -> normalize(request.prompt(), parseMap(request.vars().get("clarifications")));
            case "triage" -> triage(request.prompt());
            case "design" -> design(request.vars().getOrDefault("feature", "unknown"), request.prompt());
            case "docs" -> docs(request.vars().getOrDefault("feature", "unknown"), request.prompt());
            case "code" -> CodeTemplates.get(request.vars().get("template"));
            default -> throw new LlmException("unsupported purpose: " + request.purpose(), null);
        };
    }

    /**
     * Scores how ambiguous a request is, from 0 (precise) to 1 (vague).
     * Vague verbs, the absence of domain nouns, and very short requests raise
     * the score; numbers and "must" statements lower it.
     *
     * @param text request text (plus any clarifications)
     * @return score in [0, 1]
     */
    public static double ambiguityScore(String text) {
        List<String> words = words(text);
        double score = 0.15;
        if (words.stream().anyMatch(VAGUE::contains)) {
            score += 0.3;
        }
        if (words.stream().noneMatch(CONCRETE::contains)) {
            score += 0.3;
        }
        if (words.size() < 8) {
            score += 0.2;
        }
        if (text.matches("(?s).*\\d.*") || words.contains("must")) {
            score -= 0.2;
        }
        return Math.round(Math.max(0, Math.min(1, score)) * 100) / 100.0;
    }

    private static String normalize(String request, Map<String, String> clarifications) {
        String combined = request + " " + String.join(" ", clarifications.values());
        String lower = combined.toLowerCase(Locale.ROOT);
        double score = ambiguityScore(combined);
        String feature = detectFeature(lower);
        boolean incident = feature.equals("alias-race-fix");

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("title", titleFor(feature, request));
        spec.put("kind", incident ? "INCIDENT" : "FEATURE");
        spec.put("feature", feature);
        spec.put("goals", goals(request, clarifications));
        spec.put("acceptanceCriteria", acceptance(feature));
        spec.put("constraints", constraints(clarifications));
        spec.put("ambiguityScore", score);
        spec.put("openQuestions", score >= AMBIGUITY_THRESHOLD ? questions() : Map.of());
        return write(spec);
    }

    private static String detectFeature(String lower) {
        if (lower.contains("qr")) {
            return "qr-code";
        }
        if (lower.contains("alias") && (lower.contains("race") || lower.contains("duplicate")
                || lower.contains("overwrit"))) {
            return "alias-race-fix";
        }
        if (lower.contains("idle") || lower.contains("unused") || lower.contains("no clicks")) {
            return "idle-expiry";
        }
        return "unknown";
    }

    private static String titleFor(String feature, String request) {
        return switch (feature) {
            case "qr-code" -> "QR code sizing for short links";
            case "alias-race-fix" -> "Fix duplicate-alias race condition";
            case "idle-expiry" -> "Auto-expire idle links";
            default -> request.length() > 60 ? request.substring(0, 60) : request;
        };
    }

    private static List<String> goals(String request, Map<String, String> clarifications) {
        List<String> goals = new ArrayList<>();
        for (String part : request.split("[.;]")) {
            if (!part.isBlank()) {
                goals.add(part.trim());
            }
        }
        clarifications.values().forEach(answer -> goals.add("Clarified: " + answer.trim()));
        return goals;
    }

    private static List<String> acceptance(String feature) {
        return switch (feature) {
            case "qr-code" -> List.of(
                    "minimalVersion returns the smallest QR version (1-10, level M, byte mode) that fits the payload",
                    "payloads over 213 bytes and empty payloads are rejected",
                    "moduleCount(version) equals 17 + 4 * version",
                    "generated code compiles and its unit tests pass");
            case "alias-race-fix" -> List.of(
                    "concurrent registrations of the same alias produce exactly one winner",
                    "the first registered target is never overwritten",
                    "a regression test reproduces the race on legacy code and passes after the fix");
            case "idle-expiry" -> List.of(
                    "a link with no clicks for 30 days returns 410 Gone",
                    "any click resets the idle timer; never-clicked links count from creation",
                    "the idle limit must be positive");
            default -> List.of();
        };
    }

    private static List<String> constraints(Map<String, String> clarifications) {
        List<String> constraints = new ArrayList<>(List.of(
                "runtime needs no network access and no API keys",
                "no raw IP addresses or other PII in artifacts or logs"));
        String q3 = clarifications.get("q3");
        if (q3 != null) {
            constraints.add(q3.trim());
        }
        return constraints;
    }

    private static Map<String, String> questions() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("q1", "What should 'smarter' mean concretely: auto-expiring idle links, link previews, "
                + "or richer analytics?");
        q.put("q2", "What measurable acceptance criterion defines done (a threshold, status code or metric)?");
        q.put("q3", "Which constraints apply (privacy, latency, backwards compatibility)?");
        return q;
    }

    private static String triage(String report) {
        String lower = report.toLowerCase(Locale.ROOT);
        boolean alias = lower.contains("alias");
        boolean dataLoss = lower.contains("overwrit") || lower.contains("wrong destination");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", dataLoss ? "SEV2" : "SEV3");
        result.put("component", alias ? "alias-registration" : "unknown");
        result.put("hypothesis", alias
                ? "check-then-act race: two requests pass the 'alias free?' check before either writes"
                : "insufficient information");
        result.put("keywords", alias ? List.of("alias", "customAlias") : List.of());
        return write(result);
    }

    private static String design(String feature, String specMarkdown) {
        String body = switch (feature) {
            case "qr-code" -> """
                    ## Components
                    - `generated.qr.QrCodeSizer` — pure function: payload bytes -> minimal QR version.
                    - Capacity table: byte mode, error-correction level M, versions 1-10.

                    ## API sketch
                    - `GET /qr/{code}/size` -> `{"version": 2, "modules": 25}` (future endpoint).

                    ## Decisions
                    - Level M (15% recovery) balances density and robustness for printed links.
                    - Cap at version 10 (57x57): larger symbols scan poorly from screens.
                    """;
            case "idle-expiry" -> """
                    ## Components
                    - `generated.expiry.IdleExpiryPolicy` — decides expiry from creation and last click.

                    ## Data
                    - Uses existing click timestamps; stores no new personal data.

                    ## Decisions
                    - Evaluate at redirect time (no background sweeper), so expiry is exact and cheap.
                    """;
            default -> "## Components\n- To be defined.\n";
        };
        return "# Design: " + feature + "\n\n" + body + "\n## Source spec\n\n" + specMarkdown.lines()
                .limit(3).reduce("", (a, b) -> a + "> " + b + "\n");
    }

    private static String docs(String feature, String context) {
        return "# " + feature + "\n\nUser-facing notes generated from the approved spec.\n\n" + context + "\n";
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }

    private static Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new LlmException("clarifications are not a JSON object", e);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new LlmException("cannot serialise output", e);
        }
    }
}
