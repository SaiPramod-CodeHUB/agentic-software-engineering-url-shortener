package com.agentsdlc.orchestrator.approval;

import java.time.Instant;

/**
 * A human's answer at an approval checkpoint; every field is audited.
 *
 * @param approved  whether the work may proceed
 * @param approver  who decided
 * @param reason    why
 * @param decidedAt when
 */
public record ApprovalDecision(boolean approved, String approver, String reason, Instant decidedAt) {
}
