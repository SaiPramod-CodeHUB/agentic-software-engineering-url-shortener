package com.agentsdlc.orchestrator.engine;

/** Outcome of a whole run. */
public enum RunStatus {
    /** Every in-scope task succeeded. */
    SUCCEEDED,
    /** A gate or a human stopped the run; completed work was kept, the rest skipped. */
    SAFE_STOPPED,
    /** A task failed; completed work was compensated in reverse order, the rest skipped. */
    ROLLED_BACK
}
