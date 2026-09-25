package com.agentsdlc.orchestrator.core;

/** Thrown eagerly when a task graph is malformed (duplicate id, unknown dependency, cycle). */
public class GraphValidationException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the graph
     */
    public GraphValidationException(String message) {
        super(message);
    }
}
