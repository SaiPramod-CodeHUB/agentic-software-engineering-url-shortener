package com.agentsdlc.orchestrator;

import com.agentsdlc.orchestrator.approval.ScriptedApprovalProvider;
import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.engine.Orchestrator;
import com.agentsdlc.orchestrator.gate.Gate;
import com.agentsdlc.orchestrator.gate.StandardGates;
import com.agentsdlc.orchestrator.llm.DeterministicLlmProvider;
import com.agentsdlc.orchestrator.state.DecisionLog;
import com.agentsdlc.orchestrator.state.StateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test wiring: fixed clock, recording sleeper (no real waiting), scripted approvals, temp directory. */
final class Harness {

    final Path dir;
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    final StateStore state = new StateStore();
    final AuditLog audit;
    final DecisionLog decisions;
    final ScriptedApprovalProvider approvals;
    final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
    final Orchestrator orchestrator;

    Harness(Path dir) {
        this(dir, new ScriptedApprovalProvider("tester", Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC)));
    }

    Harness(Path dir, ScriptedApprovalProvider approvals) {
        this(dir, approvals, StandardGates.all(approvals, "requirements", "change-record"));
    }

    Harness(Path dir, ScriptedApprovalProvider approvals, List<Gate> gates) {
        this.dir = dir;
        this.approvals = approvals;
        this.audit = new AuditLog(dir.resolve("audit.jsonl"), clock);
        this.decisions = new DecisionLog(dir.resolve("decisions.jsonl"), clock);
        this.orchestrator = Orchestrator.builder().state(state).audit(audit).decisions(decisions)
                .llm(new DeterministicLlmProvider()).workDir(dir).gates(gates).parallelism(4)
                .sleeper(sleeps::add).clock(clock).build();
    }

    long events(String type, String taskId) {
        return audit.events().stream()
                .filter(e -> e.type().equals(type) && (taskId == null || taskId.equals(e.taskId()))).count();
    }
}
