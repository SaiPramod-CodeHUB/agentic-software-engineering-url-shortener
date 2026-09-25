package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import java.util.Map;

/**
 * Produces a design document for the spec's feature.
 *
 * <p>Outputs: {@code design.md}.</p>
 */
public final class DesignAgent implements Agent {

    private final String specTask;

    /**
     * Creates the agent.
     *
     * @param specTask id of the task that published the spec
     */
    public DesignAgent(String specTask) {
        this.specTask = specTask;
    }

    @Override
    public void execute(TaskContext ctx) {
        String feature = ctx.require(specTask, "spec.feature");
        String design = ctx.llm().complete(new LlmRequest("design", "Write a concise technical design.",
                ctx.require(specTask, "spec.md"), Map.of("feature", feature)));
        ctx.writeArtifact("DESIGN.md", design);
        ctx.put("design.md", design);
        ctx.decide("design for " + feature, "pure, dependency-free component keeps it testable in isolation",
                Map.of("feature", feature));
    }
}
