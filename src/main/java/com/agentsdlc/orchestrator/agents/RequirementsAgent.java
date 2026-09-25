package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.spec.NormalizedSpec;
import com.agentsdlc.orchestrator.spec.RequirementNormalizer;
import com.agentsdlc.orchestrator.state.StateStore;
import java.util.Map;

/**
 * Normalises the human request (from {@code _human/request}, plus any
 * {@code _human/clarifications}) into a versioned spec. A spec that is still
 * ambiguous is published as a {@code DRAFT} with clarifying questions; the
 * spec-ready gate then keeps build tasks from starting.
 *
 * <p>Outputs: {@code spec.status}, {@code spec.version}, {@code spec.feature},
 * {@code spec.kind}, {@code spec.md}, {@code spec.questions}.</p>
 */
public final class RequirementsAgent implements Agent {

    /** Creates the agent. */
    public RequirementsAgent() {
        // Stateless.
    }

    @Override
    public void execute(TaskContext ctx) {
        String request = ctx.require(StateStore.HUMAN_NS, "request");
        Map<String, String> clarifications = ctx.get(StateStore.HUMAN_NS, "clarifications")
                .map(AgentSupport::jsonMap).orElse(Map.of());
        int version = clarifications.isEmpty() ? 1 : 2;
        NormalizedSpec spec = new RequirementNormalizer(ctx.llm()).normalize(request, clarifications, version);

        String file = spec.status() == NormalizedSpec.Status.DRAFT
                ? "SPEC_v" + version + "_DRAFT.md" : "SPEC_v" + version + ".md";
        String markdown = spec.toMarkdown();
        ctx.writeArtifact(file, markdown);
        ctx.put("spec.status", spec.status().name());
        ctx.put("spec.version", Integer.toString(spec.version()));
        ctx.put("spec.feature", spec.feature());
        ctx.put("spec.kind", spec.kind());
        ctx.put("spec.md", markdown);
        ctx.put("spec.questions", AgentSupport.toJson(spec.openQuestions()));
        ctx.decide("spec v" + version + " is " + spec.status(),
                spec.status() == NormalizedSpec.Status.DRAFT
                        ? "ambiguity score " + spec.ambiguityScore() + " >= threshold; building now would mean guessing"
                        : "ambiguity score " + spec.ambiguityScore() + " below threshold; acceptance criteria are testable",
                Map.of("request", request, "clarifications", Integer.toString(clarifications.size()),
                        "feature", spec.feature()));
    }
}
