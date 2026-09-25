package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.TaskSpec;
import java.util.List;

/** Fails any attempt whose artifacts contain PII (emails, phone numbers, raw IPs). */
public final class ComplianceGate implements Gate {

    /** Creates the gate. */
    public ComplianceGate() {
        // Stateless.
    }

    @Override
    public String name() {
        return "compliance-pii";
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
        List<ContentScanner.Finding> findings = ContentScanner.scan(ctx.artifacts(), ContentScanner.PII_RULES);
        return findings.isEmpty()
                ? GateResult.pass("no PII in " + ctx.artifacts().size() + " artifact(s)")
                : GateResult.block("PII found: " + findings);
    }
}
