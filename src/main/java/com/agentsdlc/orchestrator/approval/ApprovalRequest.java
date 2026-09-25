package com.agentsdlc.orchestrator.approval;

import com.agentsdlc.orchestrator.core.RiskTier;

/**
 * What a human is asked to approve.
 *
 * @param runId      run id
 * @param taskId     task awaiting approval
 * @param checkpoint checkpoint name, e.g. {@code approval} or {@code final-signoff}
 * @param risk       the task's risk tier
 * @param summary    what the human is approving
 */
public record ApprovalRequest(String runId, String taskId, String checkpoint, RiskTier risk, String summary) {
}
