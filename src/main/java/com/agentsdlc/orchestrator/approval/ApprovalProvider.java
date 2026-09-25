package com.agentsdlc.orchestrator.approval;

/**
 * Source of human approval decisions. In production this would block on a
 * ticketing or chat workflow; scenarios and tests use
 * {@link ScriptedApprovalProvider} so runs are reproducible.
 */
@FunctionalInterface
public interface ApprovalProvider {

    /**
     * Asks a human to approve or reject.
     *
     * @param request what is being approved
     * @return the decision
     */
    ApprovalDecision decide(ApprovalRequest request);
}
