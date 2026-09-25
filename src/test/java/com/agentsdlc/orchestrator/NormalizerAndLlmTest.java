package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentsdlc.orchestrator.llm.DeterministicLlmProvider;
import com.agentsdlc.orchestrator.llm.FailClosedLlmProvider;
import com.agentsdlc.orchestrator.llm.LlmProvider;
import com.agentsdlc.orchestrator.llm.LlmProviders;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.agentsdlc.orchestrator.scenario.AmbiguousScenario;
import com.agentsdlc.orchestrator.scenario.BrownfieldScenario;
import com.agentsdlc.orchestrator.scenario.GreenfieldScenario;
import com.agentsdlc.orchestrator.spec.NormalizedSpec;
import com.agentsdlc.orchestrator.spec.RequirementNormalizer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NormalizerAndLlmTest {

    private final RequirementNormalizer normalizer = new RequirementNormalizer(new DeterministicLlmProvider());

    @Test
    void vagueRequestBecomesADraftWithQuestions() {
        NormalizedSpec spec = normalizer.normalize(AmbiguousScenario.REQUEST, Map.of(), 1);
        assertThat(spec.status()).isEqualTo(NormalizedSpec.Status.DRAFT);
        assertThat(spec.ambiguityScore()).isGreaterThanOrEqualTo(DeterministicLlmProvider.AMBIGUITY_THRESHOLD);
        assertThat(spec.openQuestions()).containsOnlyKeys("q1", "q2", "q3");
        assertThat(spec.toMarkdown()).startsWith("# DRAFT (DO NOT BUILD)");
    }

    @Test
    void clarificationsMakeTheSameRequestReadyWithTestableCriteria() {
        NormalizedSpec spec = normalizer.normalize(AmbiguousScenario.REQUEST, AmbiguousScenario.ANSWERS, 2);
        assertThat(spec.status()).isEqualTo(NormalizedSpec.Status.READY);
        assertThat(spec.feature()).isEqualTo("idle-expiry");
        assertThat(spec.version()).isEqualTo(2);
        assertThat(spec.acceptanceCriteria()).anyMatch(c -> c.contains("410"));
        assertThat(spec.openQuestions()).isEmpty();
    }

    @Test
    void preciseRequestsAreReadyAndClassifiedByKind() {
        NormalizedSpec qr = normalizer.normalize(GreenfieldScenario.REQUEST, Map.of(), 1);
        assertThat(qr.status()).isEqualTo(NormalizedSpec.Status.READY);
        assertThat(qr.kind()).isEqualTo("FEATURE");
        assertThat(qr.feature()).isEqualTo("qr-code");
        NormalizedSpec incident = normalizer.normalize(BrownfieldScenario.INCIDENT_REPORT, Map.of(), 1);
        assertThat(incident.kind()).isEqualTo("INCIDENT");
        assertThat(incident.feature()).isEqualTo("alias-race-fix");
    }

    @Test
    void malformedModelOutputFailsClosedToTheDeterministicProvider() {
        LlmProvider garbage = new LlmProvider() {
            @Override
            public String name() {
                return "garbage";
            }

            @Override
            public String complete(LlmRequest request) {
                return "Sure! Here is your spec: {not json";
            }
        };
        NormalizedSpec spec = new RequirementNormalizer(garbage).normalize(AmbiguousScenario.REQUEST, Map.of(), 1);
        assertThat(spec.status()).isEqualTo(NormalizedSpec.Status.DRAFT);
    }

    @Test
    void providerSelectionIsOfflineByDefaultAndWrapsHostedModelsInAFallback() {
        assertThat(LlmProviders.fromEnvironment(Map.of())).isInstanceOf(DeterministicLlmProvider.class);
        assertThat(LlmProviders.fromEnvironment(Map.of("AGENTIC_LLM", "openai"))).isInstanceOf(DeterministicLlmProvider.class);
        assertThat(LlmProviders.fromEnvironment(Map.of("AGENTIC_LLM", "openai", "OPENAI_API_KEY", "k")))
                .isInstanceOf(FailClosedLlmProvider.class);

        LlmProvider down = new LlmProvider() {
            @Override
            public String name() {
                return "down";
            }

            @Override
            public String complete(LlmRequest request) {
                throw new LlmException("connection refused", null);
            }
        };
        FailClosedLlmProvider wrapped = new FailClosedLlmProvider(down, new DeterministicLlmProvider());
        String json = wrapped.complete(new LlmRequest("triage", "", BrownfieldScenario.INCIDENT_REPORT, Map.of()));
        assertThat(json).contains("alias-registration");
        assertThat(wrapped.fallbackCount()).isEqualTo(1);
    }
}
