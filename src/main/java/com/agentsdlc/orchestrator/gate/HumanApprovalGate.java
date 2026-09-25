package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.approval.ApprovalDecision;
import com.agentsdlc.orchestrator.approval.ApprovalProvider;
import com.agentsdlc.orchestrator.approval.ApprovalRequest;
import com.agentsdlc.orchestrator.core.RiskTier;
import com.agentsdlc.orchestrator.core.Tags;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.state.StateStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Human-in-the-loop checkpoint. The request and the decision (approved or
 * rejected, by whom, when, why) are separate audit events, and the decision
 * is also stored in the reserved {@code _approvals} namespace so downstream
 * agents (e.g. the change record) can cite it.
 */
public final class HumanApprovalGate implements Gate {

    /** Checkpoint name for risk-based approvals. */
    public static final String APPROVAL = "approval";
    /** Checkpoint name for the mandatory pre-release sign-off. */
    public static final String FINAL_SIGNOFF = "final-signoff";

    private final ApprovalProvider provider;
    private final String checkpoint;
    private final Predicate<TaskSpec> selector;

    /**
     * Creates a gate.
     *
     * @param provider   source of human decisions
     * @param checkpoint checkpoint name
     * @param selector   which tasks need this checkpoint
     */
    public HumanApprovalGate(ApprovalProvider provider, String checkpoint, Predicate<TaskSpec> selector) {
        this.provider = provider;
        this.checkpoint = checkpoint;
        this.selector = selector;
    }

    /**
     * Approval for HIGH-risk or explicitly tagged tasks (releases are covered
     * by {@link #finalSignoff} instead, so humans are not asked twice).
     *
     * @param provider source of human decisions
     * @return the gate
     */
    public static HumanApprovalGate forHighRisk(ApprovalProvider provider) {
        return new HumanApprovalGate(provider, APPROVAL,
                t -> !t.hasTag(Tags.RELEASE) && (t.risk() == RiskTier.HIGH || t.hasTag(Tags.NEEDS_APPROVAL)));
    }

    /**
     * Mandatory human sign-off before any release, whatever its risk tier:
     * humans own final quality control.
     *
     * @param provider source of human decisions
     * @return the gate
     */
    public static HumanApprovalGate finalSignoff(ApprovalProvider provider) {
        return new HumanApprovalGate(provider, FINAL_SIGNOFF, t -> t.hasTag(Tags.RELEASE));
    }

    @Override
    public String name() {
        return "human-" + checkpoint;
    }

    @Override
    public Phase phase() {
        return Phase.ENTRY;
    }

    @Override
    public boolean appliesTo(TaskSpec task) {
        return selector.test(task);
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        TaskSpec task = ctx.task();
        ctx.audit().record(ctx.runId(), "APPROVAL_REQUESTED", task.id(),
                Map.of("checkpoint", checkpoint, "risk", task.risk().name(), "destructive", task.destructive()));
        ApprovalDecision decision = provider.decide(new ApprovalRequest(ctx.runId(), task.id(), checkpoint,
                task.risk(), "stage=" + task.stage() + ", tags=" + task.tags()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkpoint", checkpoint);
        data.put("approver", decision.approver());
        data.put("decidedAt", decision.decidedAt().toString());
        data.put("reason", decision.reason());
        ctx.audit().record(ctx.runId(), decision.approved() ? "APPROVAL_GRANTED" : "APPROVAL_REJECTED",
                task.id(), data);
        ctx.state().put(StateStore.APPROVALS_NS, task.id() + "#" + checkpoint,
                (decision.approved() ? "APPROVED" : "REJECTED") + " by " + decision.approver()
                        + " at " + decision.decidedAt());
        return decision.approved()
                ? GateResult.pass(checkpoint + " granted by " + decision.approver())
                : GateResult.reject(checkpoint + " rejected by " + decision.approver() + ": " + decision.reason());
    }
}
