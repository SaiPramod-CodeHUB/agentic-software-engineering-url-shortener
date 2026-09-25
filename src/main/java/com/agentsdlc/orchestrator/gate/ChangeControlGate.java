package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.Tags;
import com.agentsdlc.orchestrator.core.TaskSpec;

/** A release may only start when an upstream change record is {@code APPROVED}. */
public final class ChangeControlGate implements Gate {

    private final String changeRecordTaskId;

    /**
     * Creates the gate.
     *
     * @param changeRecordTaskId id of the task that publishes {@code status} of the change record
     */
    public ChangeControlGate(String changeRecordTaskId) {
        this.changeRecordTaskId = changeRecordTaskId;
    }

    @Override
    public String name() {
        return "change-control";
    }

    @Override
    public Phase phase() {
        return Phase.ENTRY;
    }

    @Override
    public boolean appliesTo(TaskSpec task) {
        return task.hasTag(Tags.RELEASE);
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        String status = ctx.state().get(changeRecordTaskId, "status").orElse("MISSING");
        String id = ctx.state().get(changeRecordTaskId, "change.id").orElse("?");
        return "APPROVED".equals(status)
                ? GateResult.pass("change record " + id + " is APPROVED")
                : GateResult.block("change record status is " + status);
    }
}
