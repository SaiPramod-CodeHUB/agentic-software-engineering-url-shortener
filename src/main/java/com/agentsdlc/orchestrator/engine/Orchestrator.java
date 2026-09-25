package com.agentsdlc.orchestrator.engine;

import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.core.TaskStatus;
import com.agentsdlc.orchestrator.gate.Gate;
import com.agentsdlc.orchestrator.gate.GateContext;
import com.agentsdlc.orchestrator.gate.GateResult;
import com.agentsdlc.orchestrator.llm.LlmProvider;
import com.agentsdlc.orchestrator.state.DecisionLog;
import com.agentsdlc.orchestrator.state.StateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Executes a {@link TaskGraph} wave by wave.
 *
 * <p>Execution model: tasks in a wave fan out on a bounded thread pool and
 * are joined before the next wave starts. For each task: dependency check →
 * entry gates → attempts with exponential backoff (each followed by exit
 * gates) → optional fallback. A failed task triggers saga-style rollback of
 * everything completed in the run, in reverse completion order, then a
 * safe-stop; a blocked or rejected task triggers a safe-stop without
 * rollback (nothing went wrong, a policy or human said "not yet"). Either way
 * every remaining task is explicitly marked {@link TaskStatus#SKIPPED}.</p>
 *
 * <p>The engine contains no business policy: what may start, what counts as
 * done and who must approve is decided entirely by the configured
 * {@link Gate}s.</p>
 */
public final class Orchestrator {

    private final StateStore state;
    private final AuditLog audit;
    private final DecisionLog decisions;
    private final LlmProvider llm;
    private final Path workDir;
    private final List<Gate> gates;
    private final int parallelism;
    private final Sleeper sleeper;
    private final Clock clock;
    private final AtomicInteger runCounter = new AtomicInteger();
    private final Map<String, String> openIncidents = new ConcurrentHashMap<>();

    private Orchestrator(Builder b) {
        this.state = Objects.requireNonNull(b.state, "state");
        this.audit = Objects.requireNonNull(b.audit, "audit");
        this.decisions = Objects.requireNonNull(b.decisions, "decisions");
        this.llm = Objects.requireNonNull(b.llm, "llm");
        this.workDir = Objects.requireNonNull(b.workDir, "workDir");
        this.gates = List.copyOf(b.gates);
        this.parallelism = b.parallelism;
        this.sleeper = b.sleeper;
        this.clock = b.clock;
    }

    /**
     * Starts a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs every task in the graph.
     *
     * @param pipeline pipeline name (groups runs for incidents and run ids)
     * @param graph    the plan
     * @return the run report
     */
    public RunReport run(String pipeline, TaskGraph graph) {
        return run(pipeline, graph, graph.tasks().stream().map(TaskSpec::id).collect(Collectors.toSet()));
    }

    /**
     * Runs only the tasks in {@code scope}; the others are treated as already
     * done and their stored outputs are reused. This is how re-planning
     * re-executes an invalidated subset without redoing unaffected (or
     * human-approved) work.
     *
     * @param pipeline pipeline name
     * @param graph    the plan
     * @param scope    ids of the tasks to execute
     * @return the run report
     */
    public RunReport run(String pipeline, TaskGraph graph, Set<String> scope) {
        String runId = pipeline + "-run-" + runCounter.incrementAndGet();
        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        audit.record(runId, "RUN_STARTED", null, Map.of(
                "pipeline", pipeline,
                "scope", sorted(scope),
                "waves", graph.waves().stream().map(w -> w.stream().map(TaskSpec::id).toList()).toList(),
                "llm", llm.name()));

        Map<String, TaskRecord> records = new ConcurrentHashMap<>();
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        Map<String, Map<String, String>> snapshots = new ConcurrentHashMap<>();
        String haltReason = null;
        boolean failed = false;

        try (ExecutorService pool = Executors.newFixedThreadPool(parallelism)) {
            for (List<TaskSpec> wave : graph.waves()) {
                List<TaskSpec> toRun = new ArrayList<>();
                for (TaskSpec task : wave) {
                    if (!scope.contains(task.id())) {
                        records.put(task.id(), reused(runId, task));
                    } else if (haltReason != null) {
                        records.put(task.id(), skip(runId, task, "safe-stop: " + haltReason));
                    } else {
                        toRun.add(task);
                    }
                }
                List<Callable<TaskRecord>> calls = toRun.stream()
                        .<Callable<TaskRecord>>map(t -> () -> execute(runId, t, records, completionOrder, snapshots))
                        .toList();
                // invokeAll is the join point: the next wave cannot start until every task here finished.
                List<Future<TaskRecord>> futures = pool.invokeAll(calls);
                for (Future<TaskRecord> f : futures) {
                    TaskRecord r = f.get();
                    records.put(r.id(), r);
                }
                if (haltReason == null) {
                    for (TaskSpec task : toRun) {
                        TaskRecord r = records.get(task.id());
                        if (r.status() == TaskStatus.FAILED) {
                            failed = true;
                            haltReason = "task " + task.id() + " failed: " + r.reason();
                            break;
                        }
                        if (r.status() == TaskStatus.BLOCKED || r.status() == TaskStatus.REJECTED) {
                            haltReason = "task " + task.id() + " " + r.status().name().toLowerCase() + ": " + r.reason();
                        }
                    }
                    if (failed) {
                        rollback(runId, graph, records, completionOrder, snapshots);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("run interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("task execution crashed", e.getCause());
        }

        RunStatus status = failed ? RunStatus.ROLLED_BACK
                : haltReason != null ? RunStatus.SAFE_STOPPED : RunStatus.SUCCEEDED;
        if (haltReason != null) {
            List<String> skipped = records.values().stream().filter(r -> r.status() == TaskStatus.SKIPPED)
                    .map(TaskRecord::id).sorted().toList();
            audit.record(runId, "SAFE_STOP", null, Map.of("reason", haltReason, "skipped", skipped));
        }
        trackIncident(runId, pipeline, status, haltReason);
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        audit.record(runId, "RUN_FINISHED", null, Map.of("status", status.name(), "durationMs", durationMillis));
        List<TaskRecord> ordered = graph.tasks().stream().map(t -> records.get(t.id())).toList();
        return new RunReport(runId, pipeline, status, startedAt, durationMillis, ordered, haltReason);
    }

    private TaskRecord execute(String runId, TaskSpec task, Map<String, TaskRecord> records,
                               List<String> completionOrder, Map<String, Map<String, String>> snapshots) {
        for (String dep : sorted(task.dependsOn())) {
            TaskStatus depStatus = records.containsKey(dep) ? records.get(dep).status() : storedStatus(dep);
            if (!depStatus.satisfiesDependents()) {
                return skip(runId, task, "dependency " + dep + " is " + depStatus);
            }
        }
        for (Gate gate : gates) {
            if (gate.phase() != Gate.Phase.ENTRY || !gate.appliesTo(task)) {
                continue;
            }
            GateResult result = evaluate(runId, gate, task, List.of());
            if (!result.passed()) {
                TaskStatus status = result.verdict() == GateResult.Verdict.REJECT ? TaskStatus.REJECTED : TaskStatus.BLOCKED;
                state.put(StateStore.STATUS_NS, task.id(), status.name());
                audit.record(runId, "TASK_" + status.name(), task.id(), Map.of("gate", gate.name(), "reason", result.reason()));
                return new TaskRecord(task.id(), task.stage(), status, 0, 0, result.reason(), null);
            }
        }

        // Snapshot once, before the first attempt: retries must not re-baseline on partial writes.
        Map<String, String> snapshot = state.snapshot(task.id());
        snapshots.put(task.id(), snapshot);
        long start = System.nanoTime();
        String lastFailure = null;
        int attempts = 0;
        TaskStatus outcome = null;

        for (int attempt = 1; attempt <= task.retry().maxAttempts() && outcome == null; attempt++) {
            if (attempt > 1) {
                Duration backoff = task.retry().backoffBefore(attempt);
                audit.record(runId, "TASK_RETRY", task.id(), Map.of("attempt", attempt,
                        "backoffMs", backoff.toMillis(), "cause", lastFailure));
                sleepQuietly(backoff);
            }
            attempts = attempt;
            lastFailure = attempt(runId, task, task.agent(), "primary", attempt, lastFailure, snapshot);
            if (lastFailure == null) {
                outcome = TaskStatus.SUCCEEDED;
            }
        }
        if (outcome == null && task.fallback() != null) {
            attempts++;
            audit.record(runId, "FALLBACK_STARTED", task.id(), Map.of("after", lastFailure));
            lastFailure = attempt(runId, task, task.fallback(), "fallback", attempts, lastFailure, snapshot);
            if (lastFailure == null) {
                outcome = TaskStatus.SUCCEEDED_WITH_FALLBACK;
            }
        }
        long durationMillis = (System.nanoTime() - start) / 1_000_000;

        if (outcome == null) {
            state.restore(task.id(), snapshot); // failed tasks leave no partial outputs behind
            state.put(StateStore.STATUS_NS, task.id(), TaskStatus.FAILED.name());
            audit.record(runId, "TASK_FAILED", task.id(), Map.of("attempts", attempts, "cause", lastFailure));
            return new TaskRecord(task.id(), task.stage(), TaskStatus.FAILED, attempts, durationMillis, lastFailure, null);
        }
        String hash = state.hash(task.id());
        state.put(StateStore.STATUS_NS, task.id(), outcome.name());
        completionOrder.add(task.id());
        audit.record(runId, "TASK_SUCCEEDED", task.id(), Map.of("status", outcome.name(), "attempts", attempts,
                "durationMs", durationMillis, "outputHash", hash));
        return new TaskRecord(task.id(), task.stage(), outcome, attempts, durationMillis, null, hash);
    }

    /** Runs one attempt; returns {@code null} on success or the failure message. */
    private String attempt(String runId, TaskSpec task, Agent agent, String kind, int attempt, String lastFailure,
                           Map<String, String> snapshot) {
        state.clear(task.id()); // each attempt starts from a clean namespace
        TaskContext ctx = new TaskContext(runId, task, state, decisions, audit, llm, workDir, attempt, lastFailure,
                snapshot);
        audit.record(runId, "TASK_ATTEMPT", task.id(), Map.of("attempt", attempt, "agent", kind));
        try {
            agent.execute(ctx);
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        for (Gate gate : gates) {
            if (gate.phase() == Gate.Phase.EXIT && gate.appliesTo(task)) {
                GateResult result = evaluate(runId, gate, task, ctx.artifacts());
                if (!result.passed()) {
                    return "exit gate " + gate.name() + ": " + result.reason();
                }
            }
        }
        return null;
    }

    private GateResult evaluate(String runId, Gate gate, TaskSpec task, List<Path> artifacts) {
        GateResult result;
        try {
            result = gate.evaluate(new GateContext(runId, task, state, workDir, artifacts, audit));
        } catch (RuntimeException e) {
            // A crashing gate must never wave work through: treat it as a block.
            result = GateResult.block("gate error: " + e.getMessage());
        }
        audit.record(runId, "GATE_EVALUATED", task.id(), Map.of("gate", gate.name(), "phase", gate.phase().name(),
                "verdict", result.verdict().name(), "reason", result.reason()));
        return result;
    }

    private void rollback(String runId, TaskGraph graph, Map<String, TaskRecord> records, List<String> completionOrder,
                          Map<String, Map<String, String>> snapshots) {
        List<String> order;
        synchronized (completionOrder) {
            order = new ArrayList<>(completionOrder);
        }
        Collections.reverse(order);
        audit.record(runId, "ROLLBACK_STARTED", null, Map.of("reverseCompletionOrder", order));
        for (String id : order) {
            TaskSpec task = graph.get(id);
            if (task.compensation() == null) {
                audit.record(runId, "ROLLBACK_STEP", id, Map.of("result", "no-compensation-needed"));
                continue;
            }
            Map<String, String> snapshot = snapshots.getOrDefault(id, Map.of());
            TaskContext ctx = new TaskContext(runId, task, state, decisions, audit, llm, workDir, 0, null, snapshot);
            try {
                task.compensation().compensate(ctx);
                state.restore(id, snapshot);
                state.put(StateStore.STATUS_NS, id, TaskStatus.COMPENSATED.name());
                records.computeIfPresent(id, (k, r) -> r.withStatus(TaskStatus.COMPENSATED, "rolled back"));
                audit.record(runId, "ROLLBACK_STEP", id, Map.of("result", "compensated"));
            } catch (Exception e) {
                // Best effort: keep compensating the rest, and flag this one for a human.
                audit.record(runId, "ROLLBACK_STEP_FAILED", id, Map.of("error", String.valueOf(e.getMessage()),
                        "action", "manual intervention required"));
            }
        }
        audit.record(runId, "ROLLBACK_FINISHED", null, Map.of());
    }

    private void trackIncident(String runId, String pipeline, RunStatus status, String haltReason) {
        if (status == RunStatus.ROLLED_BACK && !openIncidents.containsKey(pipeline)) {
            String incidentId = "INC-" + pipeline + "-" + runId.substring(runId.lastIndexOf('-') + 1);
            openIncidents.put(pipeline, incidentId);
            audit.record(runId, "INCIDENT_OPENED", null, Map.of("incidentId", incidentId, "pipeline", pipeline,
                    "cause", haltReason));
        } else if (status == RunStatus.SUCCEEDED && openIncidents.containsKey(pipeline)) {
            audit.record(runId, "INCIDENT_RESOLVED", null, Map.of("incidentId", openIncidents.remove(pipeline),
                    "pipeline", pipeline));
        }
    }

    private TaskRecord reused(String runId, TaskSpec task) {
        TaskStatus stored = storedStatus(task.id());
        if (stored.satisfiesDependents()) {
            audit.record(runId, "TASK_REUSED", task.id(), Map.of("outputHash", state.hash(task.id())));
            return new TaskRecord(task.id(), task.stage(), TaskStatus.REUSED, 0, 0, "outside re-plan scope",
                    state.hash(task.id()));
        }
        return new TaskRecord(task.id(), task.stage(), TaskStatus.SKIPPED, 0, 0,
                "outside scope and no earlier success (" + stored + ")", null);
    }

    private TaskRecord skip(String runId, TaskSpec task, String reason) {
        audit.record(runId, "TASK_SKIPPED", task.id(), Map.of("reason", reason));
        return new TaskRecord(task.id(), task.stage(), TaskStatus.SKIPPED, 0, 0, reason, null);
    }

    private TaskStatus storedStatus(String id) {
        return state.get(StateStore.STATUS_NS, id).map(TaskStatus::valueOf).orElse(TaskStatus.PENDING);
    }

    private void sleepQuietly(Duration d) {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> sorted(Set<String> ids) {
        return ids.stream().sorted().toList();
    }

    /**
     * Returns the incidents currently open, by pipeline.
     *
     * @return pipeline to incident id
     */
    public Map<String, String> openIncidents() {
        return new LinkedHashMap<>(openIncidents);
    }

    /** Builder for {@link Orchestrator}; state, audit, decisions, llm and workDir are required. */
    public static final class Builder {
        private StateStore state;
        private AuditLog audit;
        private DecisionLog decisions;
        private LlmProvider llm;
        private Path workDir;
        private final List<Gate> gates = new ArrayList<>();
        private int parallelism = 4;
        private Sleeper sleeper = Sleeper.real();
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * Sets the shared state store.
         *
         * @param value state store
         * @return this builder
         */
        public Builder state(StateStore value) {
            this.state = value;
            return this;
        }

        /**
         * Sets the audit log.
         *
         * @param value audit log
         * @return this builder
         */
        public Builder audit(AuditLog value) {
            this.audit = value;
            return this;
        }

        /**
         * Sets the decision log.
         *
         * @param value decision log
         * @return this builder
         */
        public Builder decisions(DecisionLog value) {
            this.decisions = value;
            return this;
        }

        /**
         * Sets the language model.
         *
         * @param value LLM provider
         * @return this builder
         */
        public Builder llm(LlmProvider value) {
            this.llm = value;
            return this;
        }

        /**
         * Sets the working directory for artifacts.
         *
         * @param value working directory
         * @return this builder
         */
        public Builder workDir(Path value) {
            this.workDir = value;
            return this;
        }

        /**
         * Adds gates, evaluated in the given order.
         *
         * @param values gates
         * @return this builder
         */
        public Builder gates(List<Gate> values) {
            this.gates.addAll(values);
            return this;
        }

        /**
         * Sets the maximum number of tasks running concurrently.
         *
         * @param value pool size, at least 1
         * @return this builder
         */
        public Builder parallelism(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("parallelism must be >= 1");
            }
            this.parallelism = value;
            return this;
        }

        /**
         * Sets the backoff sleeper.
         *
         * @param value sleeper
         * @return this builder
         */
        public Builder sleeper(Sleeper value) {
            this.sleeper = value;
            return this;
        }

        /**
         * Sets the clock used for run timestamps.
         *
         * @param value clock
         * @return this builder
         */
        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /**
         * Builds the orchestrator.
         *
         * @return the orchestrator
         */
        public Orchestrator build() {
            return new Orchestrator(this);
        }
    }
}
