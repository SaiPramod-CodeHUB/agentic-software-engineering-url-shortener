package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentsdlc.orchestrator.approval.ScriptedApprovalProvider;
import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.RetryPolicy;
import com.agentsdlc.orchestrator.core.RiskTier;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.core.TaskStatus;
import com.agentsdlc.orchestrator.engine.RunReport;
import com.agentsdlc.orchestrator.engine.RunStatus;
import com.agentsdlc.orchestrator.gate.Gate;
import com.agentsdlc.orchestrator.gate.GateContext;
import com.agentsdlc.orchestrator.gate.GateResult;
import com.agentsdlc.orchestrator.gate.RequiredOutputsGate;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrchestratorTest {

    @TempDir
    Path dir;

    @Test
    void tasksInAWaveRunConcurrentlyAndTheNextWaveSeesBothOutputs() {
        Harness h = new Harness(dir);
        // Each task waits for the other: only true parallel execution gets both past the barrier.
        CyclicBarrier both = new CyclicBarrier(2);
        TaskGraph g = new TaskGraph(List.of(
                TaskSpec.builder("left", ctx -> {
                    both.await(5, TimeUnit.SECONDS);
                    ctx.put("v", "L");
                }).build(),
                TaskSpec.builder("right", ctx -> {
                    both.await(5, TimeUnit.SECONDS);
                    ctx.put("v", "R");
                }).build(),
                TaskSpec.builder("join", ctx -> ctx.put("v", ctx.require("left", "v") + ctx.require("right", "v")))
                        .dependsOn("left", "right").build()));
        RunReport r = h.orchestrator.run("parallel", g);
        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(h.state.get("join", "v")).contains("LR");
    }

    @Test
    void retriesWithExponentialBackoffThenSucceeds() {
        Harness h = new Harness(dir);
        AtomicInteger calls = new AtomicInteger();
        TaskGraph g = new TaskGraph(List.of(TaskSpec.builder("flaky", ctx -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("transient " + calls.get());
            }
            ctx.put("ok", "yes");
        }).retry(RetryPolicy.exponential(3, Duration.ofMillis(10))).build()));
        RunReport r = h.orchestrator.run("retry", g);
        assertThat(r.task("flaky").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(r.task("flaky").attempts()).isEqualTo(3);
        assertThat(h.sleeps).containsExactly(Duration.ofMillis(10), Duration.ofMillis(20));
        assertThat(h.events("TASK_RETRY", "flaky")).isEqualTo(2);
    }

    @Test
    void fallbackRunsOnceAfterRetriesAreExhausted() {
        Harness h = new Harness(dir);
        TaskGraph g = new TaskGraph(List.of(TaskSpec.builder("summarise", ctx -> {
            throw new IllegalStateException("model timeout");
        }).retry(RetryPolicy.exponential(2, Duration.ofMillis(5))).fallback(ctx -> ctx.put("summary", "template"))
                .requires("summary").build()));
        RunReport r = h.orchestrator.run("fallback", g);
        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(r.task("summarise").status()).isEqualTo(TaskStatus.SUCCEEDED_WITH_FALLBACK);
        assertThat(r.task("summarise").attempts()).isEqualTo(3);
        assertThat(h.events("FALLBACK_STARTED", "summarise")).isEqualTo(1);
    }

    @Test
    void failureCompensatesInReverseCompletionOrderAndSkipsDownstream() {
        Harness h = new Harness(dir);
        List<String> undone = Collections.synchronizedList(new ArrayList<>());
        TaskGraph g = new TaskGraph(List.of(
                TaskSpec.builder("a", ctx -> ctx.put("x", "1")).compensation(ctx -> undone.add("a")).build(),
                TaskSpec.builder("b", ctx -> ctx.put("x", "2")).dependsOn("a").compensation(ctx -> undone.add("b"))
                        .build(),
                TaskSpec.builder("c", ctx -> {
                    throw new IllegalStateException("boom");
                }).dependsOn("b").build(),
                TaskSpec.builder("d", ctx -> ctx.put("x", "4")).dependsOn("c").build()));
        RunReport r = h.orchestrator.run("saga", g);
        assertThat(r.status()).isEqualTo(RunStatus.ROLLED_BACK);
        assertThat(undone).containsExactly("b", "a");
        assertThat(r.task("a").status()).isEqualTo(TaskStatus.COMPENSATED);
        assertThat(r.task("c").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(r.task("d").status()).isEqualTo(TaskStatus.SKIPPED);
        assertThat(h.state.snapshot("a")).isEmpty();
        assertThat(h.events("INCIDENT_OPENED", null)).isEqualTo(1);
        assertThat(h.events("SAFE_STOP", null)).isEqualTo(1);
    }

    @Test
    void snapshotIsTakenOnceBeforeTheFirstAttemptNotBeforeRetries() {
        Harness h = new Harness(dir);
        h.state.put("migrate", "baseline", "v1");
        TaskGraph g = new TaskGraph(List.of(
                TaskSpec.builder("migrate", ctx -> {
                    ctx.put("partial", "attempt-" + ctx.attempt());
                    if (ctx.attempt() == 1) {
                        throw new IllegalStateException("partial write then crash");
                    }
                    ctx.put("done", "true");
                }).retry(RetryPolicy.exponential(2, Duration.ofMillis(1))).compensation(ctx -> { }).build(),
                TaskSpec.builder("deploy", ctx -> {
                    throw new IllegalStateException("deploy failed");
                }).dependsOn("migrate").build()));
        h.orchestrator.run("snapshot", g);
        assertThat(h.state.snapshot("migrate")).isEqualTo(Map.of("baseline", "v1"));
    }

    @Test
    void humanRejectionSafeStopsWithoutRollback() {
        ScriptedApprovalProvider approvals = new ScriptedApprovalProvider("lead", Clock.systemUTC())
                .reject("drop", "approval", "not today");
        Harness h = new Harness(dir, approvals);
        AtomicInteger compensations = new AtomicInteger();
        TaskGraph g = new TaskGraph(List.of(
                TaskSpec.builder("prep", ctx -> ctx.put("ok", "1")).compensation(ctx -> compensations.incrementAndGet())
                        .build(),
                TaskSpec.builder("drop", ctx -> ctx.put("ok", "1")).dependsOn("prep").risk(RiskTier.HIGH)
                        .destructive().build(),
                TaskSpec.builder("after", ctx -> ctx.put("ok", "1")).dependsOn("drop").build()));
        RunReport r = h.orchestrator.run("reject", g);
        assertThat(r.status()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(r.task("drop").status()).isEqualTo(TaskStatus.REJECTED);
        assertThat(r.task("after").status()).isEqualTo(TaskStatus.SKIPPED);
        assertThat(r.task("prep").status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(compensations).hasValue(0);
        List<AuditLog.Event> rejected = h.audit.events().stream()
                .filter(e -> e.type().equals("APPROVAL_REJECTED")).toList();
        assertThat(rejected).singleElement().satisfies(e -> {
            assertThat(e.data()).containsEntry("approver", "lead").containsEntry("reason", "not today");
            assertThat(e.data()).containsKey("decidedAt");
        });
    }

    @Test
    void destructiveTaskDeclaredLowRiskIsBlockedBeforeAnyAttempt() {
        Harness h = new Harness(dir);
        AtomicInteger ran = new AtomicInteger();
        TaskGraph g = new TaskGraph(List.of(TaskSpec.builder("wipe", ctx -> ran.incrementAndGet())
                .risk(RiskTier.LOW).destructive().build()));
        RunReport r = h.orchestrator.run("policy", g);
        assertThat(r.task("wipe").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(ran).hasValue(0);
        assertThat(h.approvals.requests()).isEmpty();
    }

    @Test
    void missingRequiredOutputFailsTheAttempt() {
        Harness h = new Harness(dir);
        TaskGraph g = new TaskGraph(List.of(TaskSpec.builder("lazy", ctx -> ctx.put("other", "x"))
                .requires("report").build()));
        RunReport r = h.orchestrator.run("contract", g);
        assertThat(r.task("lazy").status()).isEqualTo(TaskStatus.FAILED);
        assertThat(r.task("lazy").reason()).contains("missing outputs: [report]");
        assertThat(h.state.snapshot("lazy")).isEmpty();
    }

    @Test
    void aCrashingGateBlocksInsteadOfWavingWorkThrough() {
        Gate broken = new Gate() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public Phase phase() {
                return Phase.ENTRY;
            }

            @Override
            public boolean appliesTo(TaskSpec task) {
                return true;
            }

            @Override
            public GateResult evaluate(GateContext ctx) {
                throw new IllegalStateException("policy service down");
            }
        };
        Harness h = new Harness(dir, new ScriptedApprovalProvider("x", Clock.systemUTC()),
                List.of(broken, new RequiredOutputsGate()));
        RunReport r = h.orchestrator.run("gate-crash",
                new TaskGraph(List.of(TaskSpec.builder("t", ctx -> ctx.put("k", "v")).build())));
        assertThat(r.task("t").status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(r.task("t").reason()).contains("gate error");
    }

    @Test
    void scopedRunReusesOutOfScopeTasksAndRunsOnlyTheScope() {
        Harness h = new Harness(dir);
        AtomicInteger upstreamRuns = new AtomicInteger();
        TaskGraph g = new TaskGraph(List.of(
                TaskSpec.builder("up", ctx -> {
                    upstreamRuns.incrementAndGet();
                    ctx.put("v", "1");
                }).build(),
                TaskSpec.builder("down", ctx -> ctx.put("v", ctx.require("up", "v") + "!")).dependsOn("up").build()));
        h.orchestrator.run("scope", g);
        RunReport second = h.orchestrator.run("scope", g, Set.of("down"));
        assertThat(upstreamRuns).hasValue(1);
        assertThat(second.task("up").status()).isEqualTo(TaskStatus.REUSED);
        assertThat(second.task("down").status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void recoveryRunResolvesThePipelineIncident() {
        Harness h = new Harness(dir);
        AtomicInteger mode = new AtomicInteger(1);
        TaskGraph g = new TaskGraph(List.of(TaskSpec.builder("deploy", ctx -> {
            if (mode.get() == 1) {
                throw new IllegalStateException("down");
            }
            ctx.put("ok", "1");
        }).build()));
        h.orchestrator.run("svc", g);
        mode.set(2);
        h.orchestrator.run("svc", g);
        assertThat(h.events("INCIDENT_OPENED", null)).isEqualTo(1);
        assertThat(h.events("INCIDENT_RESOLVED", null)).isEqualTo(1);
        assertThat(h.orchestrator.openIncidents()).isEmpty();
    }
}
