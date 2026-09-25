package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.state.StateStore;
import java.nio.file.Path;
import java.util.List;

/**
 * Everything a gate may inspect.
 *
 * @param runId     run id
 * @param task      the task being gated
 * @param state     shared state (read it; gates must not write task outputs)
 * @param workDir   scenario working directory
 * @param artifacts files the task wrote (empty for entry gates)
 * @param audit     audit log, for gates that record their own events (approvals)
 */
public record GateContext(String runId, TaskSpec task, StateStore state, Path workDir, List<Path> artifacts,
                          AuditLog audit) {

    /**
     * Copies the artifact list.
     *
     * @param runId     run id
     * @param task      task
     * @param state     state store
     * @param workDir   working directory
     * @param artifacts artifacts
     * @param audit     audit log
     */
    public GateContext {
        artifacts = List.copyOf(artifacts);
    }
}
