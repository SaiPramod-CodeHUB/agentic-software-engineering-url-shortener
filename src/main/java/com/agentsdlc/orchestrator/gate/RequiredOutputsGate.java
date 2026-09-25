package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.TaskSpec;
import java.util.List;

/** Enforces the output contract: every declared output key must be written. */
public final class RequiredOutputsGate implements Gate {

    /** Creates the gate. */
    public RequiredOutputsGate() {
        // Stateless.
    }

    @Override
    public String name() {
        return "required-outputs";
    }

    @Override
    public Phase phase() {
        return Phase.EXIT;
    }

    @Override
    public boolean appliesTo(TaskSpec task) {
        return !task.requiredOutputs().isEmpty();
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        List<String> missing = ctx.task().requiredOutputs().stream()
                .filter(key -> ctx.state().get(ctx.task().id(), key).isEmpty())
                .toList();
        return missing.isEmpty()
                ? GateResult.pass("outputs present: " + ctx.task().requiredOutputs())
                : GateResult.block("missing outputs: " + missing);
    }
}
