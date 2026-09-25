package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.Tags;
import com.agentsdlc.orchestrator.core.TaskSpec;

/** Blocks build work while the spec is still a {@code DRAFT}. */
public final class SpecReadyGate implements Gate {

    private final String specTaskId;

    /**
     * Creates the gate.
     *
     * @param specTaskId id of the task that publishes {@code spec.status}
     */
    public SpecReadyGate(String specTaskId) {
        this.specTaskId = specTaskId;
    }

    @Override
    public String name() {
        return "spec-ready";
    }

    @Override
    public Phase phase() {
        return Phase.ENTRY;
    }

    @Override
    public boolean appliesTo(TaskSpec task) {
        return task.hasTag(Tags.REQUIRES_READY_SPEC);
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        String status = ctx.state().get(specTaskId, "spec.status").orElse("MISSING");
        return "READY".equals(status)
                ? GateResult.pass("spec is READY")
                : GateResult.block("spec status is " + status + "; answer the clarifying questions first");
    }
}
