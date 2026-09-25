package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.approval.ApprovalProvider;
import java.util.List;

/**
 * The default gate chain. Order matters for entry gates: cheap policy checks
 * run first and human checkpoints last, so nobody is asked to approve work
 * that a policy would block anyway.
 */
public final class StandardGates {

    private StandardGates() {
    }

    /**
     * Builds the standard gate list.
     *
     * @param approvals          source of human decisions
     * @param specTaskId         task publishing {@code spec.status}
     * @param changeRecordTaskId task publishing the change-record {@code status}
     * @return gates in evaluation order
     */
    public static List<Gate> all(ApprovalProvider approvals, String specTaskId, String changeRecordTaskId) {
        return List.of(
                new DestructivePolicyGate(),
                new SpecReadyGate(specTaskId),
                new ImpactAnalysisGate(),
                new ChangeControlGate(changeRecordTaskId),
                HumanApprovalGate.forHighRisk(approvals),
                HumanApprovalGate.finalSignoff(approvals),
                new RequiredOutputsGate(),
                new SecretsScanGate(),
                new ComplianceGate(),
                new TestsMustPassGate());
    }
}
