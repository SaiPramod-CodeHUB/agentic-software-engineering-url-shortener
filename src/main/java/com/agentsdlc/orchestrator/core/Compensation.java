package com.agentsdlc.orchestrator.core;

/**
 * Undo action for a completed task (the "C" in a saga). Runs during rollback
 * in reverse completion order, after which the orchestrator also restores
 * the task's state namespace to the snapshot taken before its first attempt.
 */
@FunctionalInterface
public interface Compensation {

    /**
     * Reverts the task's external side effects (files, databases, deployments).
     *
     * @param ctx the task's context, including {@link TaskContext#snapshotOf(String)}
     * @throws Exception when compensation fails; the orchestrator audits it and continues
     */
    void compensate(TaskContext ctx) throws Exception;
}
