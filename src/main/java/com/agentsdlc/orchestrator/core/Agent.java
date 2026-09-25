package com.agentsdlc.orchestrator.core;

/**
 * A unit of work in the pipeline.
 *
 * <p>Agents never hold references to each other. They read upstream results
 * and publish their own through the {@link TaskContext}, which is backed by
 * the namespaced state store; that makes every hand-off visible, hashable
 * (for re-planning) and auditable.</p>
 */
@FunctionalInterface
public interface Agent {

    /**
     * Performs the task. Throwing signals a failed attempt, which the
     * orchestrator may retry, fall back from, or roll back.
     *
     * @param ctx the task's view of shared state, artifacts and services
     * @throws Exception when the attempt fails
     */
    void execute(TaskContext ctx) throws Exception;
}
