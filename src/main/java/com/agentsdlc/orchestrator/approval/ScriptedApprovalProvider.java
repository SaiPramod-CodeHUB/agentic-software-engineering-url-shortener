package com.agentsdlc.orchestrator.approval;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic stand-in for a human approver: approves everything except
 * the checkpoints it was scripted to reject, and records every request so
 * tests can assert who was asked what.
 */
public final class ScriptedApprovalProvider implements ApprovalProvider {

    private final String approver;
    private final Clock clock;
    private final Map<String, String> rejections = new HashMap<>();
    private final List<ApprovalRequest> requests = new ArrayList<>();

    /**
     * Creates a provider that approves by default.
     *
     * @param approver name recorded on every decision
     * @param clock    time source for decision timestamps
     */
    public ScriptedApprovalProvider(String approver, Clock clock) {
        this.approver = approver;
        this.clock = clock;
    }

    /**
     * Scripts a rejection.
     *
     * @param taskId     task to reject
     * @param checkpoint checkpoint at which to reject
     * @param reason     rejection reason
     * @return this provider
     */
    public ScriptedApprovalProvider reject(String taskId, String checkpoint, String reason) {
        rejections.put(taskId + "#" + checkpoint, reason);
        return this;
    }

    @Override
    public synchronized ApprovalDecision decide(ApprovalRequest request) {
        requests.add(request);
        String reason = rejections.get(request.taskId() + "#" + request.checkpoint());
        if (reason != null) {
            return new ApprovalDecision(false, approver, reason, clock.instant());
        }
        return new ApprovalDecision(true, approver, "reviewed and approved", clock.instant());
    }

    /**
     * Returns every request received.
     *
     * @return requests in arrival order
     */
    public synchronized List<ApprovalRequest> requests() {
        return List.copyOf(requests);
    }
}
