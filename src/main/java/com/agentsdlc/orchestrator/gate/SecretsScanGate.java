package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.TaskSpec;
import java.util.List;

/** Fails any attempt whose artifacts contain credential-like strings. */
public final class SecretsScanGate implements Gate {

    /** Creates the gate. */
    public SecretsScanGate() {
        // Stateless.
    }

    @Override
    public String name() {
        return "secrets-scan";
    }

    @Override
    public Phase phase() {
        return Phase.EXIT;
    }

    @Override
    public boolean appliesTo(TaskSpec task) {
        return true;
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        List<ContentScanner.Finding> findings = ContentScanner.scan(ctx.artifacts(), ContentScanner.SECRET_RULES);
        return findings.isEmpty()
                ? GateResult.pass("no secrets in " + ctx.artifacts().size() + " artifact(s)")
                : GateResult.block("possible secrets: " + findings);
    }
}
