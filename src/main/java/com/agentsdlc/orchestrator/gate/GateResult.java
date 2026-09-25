package com.agentsdlc.orchestrator.gate;

/**
 * Verdict of a gate evaluation.
 *
 * @param verdict whether the task may proceed, and if not, why not
 * @param reason  human-readable explanation, recorded in the audit log
 */
public record GateResult(Verdict verdict, String reason) {

    /** Possible verdicts. */
    public enum Verdict {
        /** The task may proceed. */
        PASS,
        /** A policy refused the task. */
        BLOCK,
        /** A human rejected the task. */
        REJECT
    }

    /**
     * A passing result.
     *
     * @param reason explanation
     * @return the result
     */
    public static GateResult pass(String reason) {
        return new GateResult(Verdict.PASS, reason);
    }

    /**
     * A policy block.
     *
     * @param reason explanation
     * @return the result
     */
    public static GateResult block(String reason) {
        return new GateResult(Verdict.BLOCK, reason);
    }

    /**
     * A human rejection.
     *
     * @param reason explanation
     * @return the result
     */
    public static GateResult reject(String reason) {
        return new GateResult(Verdict.REJECT, reason);
    }

    /**
     * Whether the task may proceed.
     *
     * @return {@code true} for {@link Verdict#PASS}
     */
    public boolean passed() {
        return verdict == Verdict.PASS;
    }
}
