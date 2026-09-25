package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.llm.LlmOutput;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.agentsdlc.orchestrator.state.StateStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Classifies the incident report, opens the incident (audited, starting the
 * MTTR clock) and hands keywords to impact analysis.
 *
 * <p>Outputs: {@code incident.id}, {@code severity}, {@code component}, {@code keywords}.</p>
 */
public final class TriageAgent implements Agent {

    private final String incidentId;

    /**
     * Creates the agent.
     *
     * @param incidentId id to open the incident under
     */
    public TriageAgent(String incidentId) {
        this.incidentId = incidentId;
    }

    @Override
    public void execute(TaskContext ctx) throws Exception {
        String report = ctx.require(StateStore.HUMAN_NS, "incident-report");
        JsonNode triage = AgentSupport.JSON.readTree(LlmOutput.stripFences(ctx.llm().complete(
                new LlmRequest("triage", "Classify the incident.", report, Map.of()))));
        String severity = triage.path("severity").asText("SEV3");
        String component = triage.path("component").asText("unknown");
        ctx.put("incident.id", incidentId);
        ctx.put("severity", severity);
        ctx.put("component", component);
        ctx.put("keywords", triage.path("keywords").toString());
        ctx.writeArtifact("TRIAGE.md", "# Triage " + incidentId + "\n\n- Severity: " + severity + "\n- Component: "
                + component + "\n- Hypothesis: " + triage.path("hypothesis").asText() + "\n");
        ctx.audit("INCIDENT_OPENED", Map.of("incidentId", incidentId, "severity", severity, "component", component));
        ctx.decide("classified as " + severity + " in " + component, triage.path("hypothesis").asText(),
                Map.of("report", report));
    }
}
