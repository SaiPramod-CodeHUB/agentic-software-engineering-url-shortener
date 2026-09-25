package com.agentsdlc.orchestrator.approval;

import java.time.Clock;

/**
 * A real human approver at the terminal. Anything other than {@code y} or
 * {@code yes} is a rejection, and so is end of input: when nobody can answer,
 * high-impact work does not proceed (fail closed). With {@code autoApprove}
 * every request is granted and recorded as such, for unattended demos.
 */
public final class ConsoleApprovalProvider implements ApprovalProvider {

    private final ConsolePrompter prompter;
    private final String approver;
    private final boolean autoApprove;
    private final Clock clock;

    /**
     * Creates the provider.
     *
     * @param prompter    terminal channel
     * @param approver    name recorded on each decision
     * @param autoApprove approve everything without asking
     * @param clock       time source for decision timestamps
     */
    public ConsoleApprovalProvider(ConsolePrompter prompter, String approver, boolean autoApprove, Clock clock) {
        this.prompter = prompter;
        this.approver = approver;
        this.autoApprove = autoApprove;
        this.clock = clock;
    }

    @Override
    public ApprovalDecision decide(ApprovalRequest request) {
        if (autoApprove) {
            prompter.say("[approval] " + request.checkpoint() + " for " + request.taskId() + " auto-approved");
            return new ApprovalDecision(true, approver + " (auto-approve)", "auto-approve flag set", clock.instant());
        }
        String answer = prompter.ask("\n[approval needed] " + request.checkpoint() + " for task '" + request.taskId()
                + "' (risk " + request.risk() + ", " + request.summary() + ")\n  Approve? [y/N]: ")
                .orElse("");
        boolean approved = answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
        String reason = approved ? "approved at console" : answer.isEmpty() ? "no answer" : "rejected at console";
        return new ApprovalDecision(approved, approver, reason, clock.instant());
    }
}
