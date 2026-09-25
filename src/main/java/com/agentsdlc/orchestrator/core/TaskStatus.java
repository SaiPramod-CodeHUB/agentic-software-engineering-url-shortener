package com.agentsdlc.orchestrator.core;

/** Lifecycle state of a task within one run. */
public enum TaskStatus {
    /** Not yet considered. */
    PENDING,
    /** Completed by its primary agent. */
    SUCCEEDED,
    /** Primary agent exhausted its retries; the fallback agent completed it. */
    SUCCEEDED_WITH_FALLBACK,
    /** Outside the run's scope; its earlier outputs are reused unchanged. */
    REUSED,
    /** An entry gate refused to let the task start. */
    BLOCKED,
    /** A human rejected the task at an approval checkpoint. */
    REJECTED,
    /** Failed after retries and fallback. */
    FAILED,
    /** Completed, then undone by its compensation during rollback. */
    COMPENSATED,
    /** Never started because the pipeline safe-stopped or a dependency did not succeed. */
    SKIPPED;

    /**
     * Whether downstream tasks may consume this task's outputs.
     *
     * @return {@code true} for successful or reused tasks
     */
    public boolean satisfiesDependents() {
        return this == SUCCEEDED || this == SUCCEEDED_WITH_FALLBACK || this == REUSED;
    }
}
