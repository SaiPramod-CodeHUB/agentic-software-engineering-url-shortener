package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.gate.HumanApprovalGate;
import com.agentsdlc.orchestrator.state.StateStore;
import java.util.List;
import java.util.Map;

/**
 * Drafts the change record (what changes, risk, rollback plan, evidence) and
 * marks it {@code APPROVED} only if a human approved this task at its
 * approval checkpoint. The change-control gate on the release reads this.
 *
 * <p>Outputs: {@code change.id}, {@code status}.</p>
 */
public final class ChangeRecordAgent implements Agent {

    private final String changeId;
    private final List<String> evidenceTasks;

    /**
     * Creates the agent.
     *
     * @param changeId      change identifier, e.g. {@code CHG-QR-001}
     * @param evidenceTasks tasks whose test results are cited as evidence
     */
    public ChangeRecordAgent(String changeId, List<String> evidenceTasks) {
        this.changeId = changeId;
        this.evidenceTasks = List.copyOf(evidenceTasks);
    }

    @Override
    public void execute(TaskContext ctx) {
        String approval = ctx.get(StateStore.APPROVALS_NS, ctx.task().id() + "#" + HumanApprovalGate.APPROVAL)
                .orElse("MISSING");
        String status = approval.startsWith("APPROVED") ? "APPROVED" : "PENDING";
        StringBuilder md = new StringBuilder("# Change record " + changeId + "\n\n- Status: " + status
                + "\n- Approval: " + approval + "\n- Rollback plan: revert the generated change set; "
                + "compensations run in reverse completion order\n\n## Evidence\n\n");
        for (String task : evidenceTasks) {
            md.append("- ").append(task).append(": tests run=").append(ctx.get(task, "tests.run").orElse("n/a"))
                    .append(", failed=").append(ctx.get(task, "tests.failed").orElse("n/a")).append("\n");
        }
        ctx.writeArtifact("CHANGE_RECORD.md", md.toString());
        ctx.put("change.id", changeId);
        ctx.put("status", status);
        ctx.decide("change record " + changeId + " " + status, "status mirrors the human approval checkpoint",
                Map.of("approval", approval));
    }
}
