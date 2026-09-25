package com.agentsdlc.orchestrator.spec;

import com.agentsdlc.orchestrator.llm.DeterministicLlmProvider;
import com.agentsdlc.orchestrator.llm.LlmOutput;
import com.agentsdlc.orchestrator.llm.LlmProvider;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a natural-language request (plus any human clarifications) into a
 * {@link NormalizedSpec}.
 *
 * <p>The model proposes; the normaliser decides. The DRAFT/READY verdict is
 * computed here from the returned ambiguity score and the presence of
 * clarifications, not taken from free text, and malformed model output fails
 * closed to the deterministic provider instead of producing a half-parsed
 * spec.</p>
 */
public final class RequirementNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM = "Normalise the software request into JSON with keys title, kind, feature, "
            + "goals, acceptanceCriteria, constraints, ambiguityScore (0-1), openQuestions (id->question).";

    private final LlmProvider llm;
    private final DeterministicLlmProvider fallback = new DeterministicLlmProvider();

    /**
     * Creates the normaliser.
     *
     * @param llm the model used to draft the spec
     */
    public RequirementNormalizer(LlmProvider llm) {
        this.llm = llm;
    }

    /**
     * Normalises a request.
     *
     * @param request        original request text
     * @param clarifications human answers by question id (empty if none)
     * @param version        version number to assign
     * @return the spec; {@code DRAFT} when ambiguous and not yet clarified
     */
    public NormalizedSpec normalize(String request, Map<String, String> clarifications, int version) {
        LlmRequest call = new LlmRequest("normalize", SYSTEM, request,
                Map.of("clarifications", writeJson(clarifications)));
        try {
            return fromJson(JSON.readTree(LlmOutput.stripFences(llm.complete(call))), request, clarifications, version);
        } catch (JsonProcessingException | RuntimeException e) {
            // Fail closed: malformed or incomplete model output never becomes a spec.
            try {
                return fromJson(JSON.readTree(fallback.complete(call)), request, clarifications, version);
            } catch (JsonProcessingException inner) {
                throw new IllegalStateException("deterministic normaliser produced invalid JSON", inner);
            }
        }
    }

    private static NormalizedSpec fromJson(JsonNode node, String request, Map<String, String> clarifications,
                                           int version) {
        if (!node.hasNonNull("ambiguityScore") || !node.hasNonNull("feature")) {
            throw new IllegalStateException("model output is missing required fields");
        }
        double score = node.get("ambiguityScore").asDouble();
        Map<String, String> questions = JSON.convertValue(node.path("openQuestions"),
                new TypeReference<Map<String, String>>() { });
        boolean ambiguous = score >= DeterministicLlmProvider.AMBIGUITY_THRESHOLD;
        String feature = node.get("feature").asText();
        NormalizedSpec.Status status = ambiguous || "unknown".equals(feature)
                ? NormalizedSpec.Status.DRAFT : NormalizedSpec.Status.READY;
        return new NormalizedSpec(version, request, node.path("title").asText(request),
                node.path("kind").asText("FEATURE"), feature,
                list(node, "goals"), list(node, "acceptanceCriteria"), list(node, "constraints"),
                score, questions == null ? Map.of() : questions, clarifications, status);
    }

    private static List<String> list(JsonNode node, String field) {
        List<String> values = JSON.convertValue(node.path(field), new TypeReference<List<String>>() { });
        return values == null ? List.of() : values;
    }

    private static String writeJson(Map<String, String> map) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(map));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
