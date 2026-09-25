package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.gate.HumanApprovalGate;
import com.agentsdlc.orchestrator.state.StateStore;
import java.util.Map;

/**
 * Publishes release notes citing the change record and the human sign-off.
 * If an upstream triage task opened an incident, the release resolves it,
 * which closes the incident pair used for MTTR.
 *
 * <p>Outputs: {@code version}, {@code notes.path}.</p>
 */
public final class ReleaseAgent implements Agent {

    private final String version;
    private final String changeRecordTask;

    /**
     * Creates the agent.
     *
     * @param version          version being released
     * @param changeRecordTask id of the change-record task
     */
    public ReleaseAgent(String version, String changeRecordTask) {
        this.version = version;
        this.changeRecordTask = changeRecordTask;
    }

    @Override
    public void execute(TaskContext ctx) {
        String signoff = ctx.require(StateStore.APPROVALS_NS, ctx.task().id() + "#" + HumanApprovalGate.FINAL_SIGNOFF);
        String changeId = ctx.require(changeRecordTask, "change.id");
        ctx.writeArtifact("RELEASE_NOTES.md", "# Release " + version + "\n\n- Change record: " + changeId
                + "\n- Final sign-off: " + signoff + "\n");
        ctx.put("version", version);
        ctx.put("notes.path", "RELEASE_NOTES.md");
        ctx.get("triage", "incident.id").ifPresent(incident ->
                ctx.audit("INCIDENT_RESOLVED", Map.of("incidentId", incident, "resolvedBy", version)));
    }
}
