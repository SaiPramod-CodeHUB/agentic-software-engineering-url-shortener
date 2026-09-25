package com.agentsdlc.orchestrator.engine;

import com.agentsdlc.orchestrator.core.TaskStatus;

/**
 * Result of one task within a run.
 *
 * @param id             task id
 * @param stage          SDLC stage
 * @param status         terminal status
 * @param attempts       attempts made (including a fallback attempt)
 * @param durationMillis time spent executing, 0 when not executed
 * @param reason         why the task ended in this status
 * @param outputHash     hash of the task's namespace after success, or {@code null}
 */
public record TaskRecord(String id, String stage, TaskStatus status, int attempts, long durationMillis,
                         String reason, String outputHash) {

    /**
     * Returns a copy with a different status and reason (used by rollback).
     *
     * @param newStatus new status
     * @param newReason new reason
     * @return the updated record
     */
    public TaskRecord withStatus(TaskStatus newStatus, String newReason) {
        return new TaskRecord(id, stage, newStatus, attempts, durationMillis, newReason, outputHash);
    }

    /**
     * Whether the task actually ran an agent in this run.
     *
     * @return {@code true} when at least one attempt was made
     */
    public boolean executed() {
        return attempts > 0;
    }
}
