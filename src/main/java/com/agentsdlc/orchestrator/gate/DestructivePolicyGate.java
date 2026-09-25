package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.RiskTier;
import com.agentsdlc.orchestrator.core.TaskSpec;

/**
 * Guardrail: a destructive task declared {@link RiskTier#LOW} is
 * mis-classified — it would dodge human approval — so it is hard-blocked.
 */
public final class DestructivePolicyGate implements Gate {

    /** Creates the gate. */
    public DestructivePolicyGate() {
        // Stateless.
    }

    @Override
    public String name() {
        return "destructive-policy";
    }

    @Override
    public Phase phase() {
        return Phase.ENTRY;
    }

    @Override
    public boolean appliesTo(TaskSpec task) {
        return task.destructive();
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        if (ctx.task().risk() == RiskTier.LOW) {
            return GateResult.block("destructive task declared LOW risk; reclassify as MEDIUM or HIGH");
        }
        return GateResult.pass("destructive task has risk " + ctx.task().risk());
    }
}
