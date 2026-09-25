package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.TaskSpec;

/**
 * A pluggable policy evaluated around task execution. Entry gates answer
 * "may this start?"; exit gates answer "did it deliver?". Gates select the
 * tasks they govern themselves ({@link #appliesTo}), so the engine stays
 * policy-free and new rules are added without touching it.
 */
public interface Gate {

    /** When a gate runs. */
    enum Phase {
        /** Before the first attempt. */
        ENTRY,
        /** After each attempt, before the task counts as done. */
        EXIT
    }

    /**
     * Stable gate name used in audit records.
     *
     * @return gate name
     */
    String name();

    /**
     * When this gate runs.
     *
     * @return the phase
     */
    Phase phase();

    /**
     * Whether this gate governs the task.
     *
     * @param task the task
     * @return {@code true} to evaluate the gate for this task
     */
    boolean appliesTo(TaskSpec task);

    /**
     * Evaluates the gate.
     *
     * @param ctx evaluation context
     * @return the verdict
     */
    GateResult evaluate(GateContext ctx);
}
