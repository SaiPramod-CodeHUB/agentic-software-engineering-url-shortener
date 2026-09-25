package com.agentsdlc.orchestrator.scenario;

import com.agentsdlc.orchestrator.approval.ApprovalProvider;
import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.engine.Orchestrator;
import com.agentsdlc.orchestrator.engine.RunReport;
import com.agentsdlc.orchestrator.gate.ContentScanner;
import com.agentsdlc.orchestrator.gate.StandardGates;
import com.agentsdlc.orchestrator.llm.LlmProvider;
import com.agentsdlc.orchestrator.llm.LlmProviders;
import com.agentsdlc.orchestrator.metrics.Metrics;
import com.agentsdlc.orchestrator.metrics.MetricsReport;
import com.agentsdlc.orchestrator.spec.Decomposer;
import com.agentsdlc.orchestrator.state.DecisionLog;
import com.agentsdlc.orchestrator.state.StateStore;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Shared wiring for a scenario: a clean working directory, the state store,
 * audit and decision logs, the LLM, a scripted approver and an orchestrator
 * with the standard gates. It also collects run reports and named checks and
 * writes the evidence ({@code metrics.json}, {@code SCENARIO_REPORT.md}).
 */
public final class ScenarioEnvironment implements AutoCloseable {

    /**
     * A named verification made by the scenario.
     *
     * @param name   what was checked
     * @param passed whether it held
     * @param detail observed values
     */
    public record Check(String name, boolean passed, String detail) {
    }

    private final String name;
    private final Path workDir;
    private final Path repoRoot;
    private final StateStore state = new StateStore();
    private final AuditLog audit;
    private final DecisionLog decisions;
    private final LlmProvider llm;
    private final ApprovalProvider approvals;
    private final Orchestrator orchestrator;
    private final List<RunReport> runs = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private final PrintStream out;

    /**
     * Creates the environment, wiping any previous output of the same scenario.
     *
     * @param name      scenario name (also the working directory name)
     * @param outRoot   parent directory for scenario output
     * @param repoRoot  repository root analysed by the agents
     * @param approvals human approver (scripted, or the console for live runs)
     * @param out       console for progress output
     */
    public ScenarioEnvironment(String name, Path outRoot, Path repoRoot, ApprovalProvider approvals,
                               PrintStream out) {
        this(name, outRoot, repoRoot, approvals, LlmProviders.fromEnvironment(), out);
    }

    /**
     * Creates the environment with an explicit LLM provider (used by tests and live runs).
     *
     * @param name      scenario name (also the working directory name)
     * @param outRoot   parent directory for scenario output
     * @param repoRoot  repository root analysed by the agents
     * @param approvals human approver
     * @param llm       language model
     * @param out       console for progress output
     */
    public ScenarioEnvironment(String name, Path outRoot, Path repoRoot, ApprovalProvider approvals,
                               LlmProvider llm, PrintStream out) {
        this.name = name;
        this.workDir = outRoot.resolve(name).toAbsolutePath().normalize();
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.out = out;
        deleteRecursively(workDir);
        Clock clock = Clock.systemUTC();
        this.audit = new AuditLog(workDir.resolve("audit.jsonl"), clock);
        this.decisions = new DecisionLog(workDir.resolve("decisions.jsonl"), clock);
        this.llm = llm;
        this.approvals = approvals;
        this.orchestrator = Orchestrator.builder().state(state).audit(audit).decisions(decisions).llm(llm)
                .workDir(workDir).parallelism(4)
                .gates(StandardGates.all(approvals, Decomposer.SPEC_TASK, Decomposer.CHANGE_TASK))
                .clock(clock).build();
    }

    /**
     * Runs a whole graph and records the report.
     *
     * @param pipeline pipeline name
     * @param graph    the plan
     * @return the report
     */
    public RunReport run(String pipeline, TaskGraph graph) {
        return record(orchestrator.run(pipeline, graph));
    }

    /**
     * Runs a subset of a graph and records the report.
     *
     * @param pipeline pipeline name
     * @param graph    the plan
     * @param scope    task ids to execute
     * @return the report
     */
    public RunReport run(String pipeline, TaskGraph graph, Set<String> scope) {
        return record(orchestrator.run(pipeline, graph, scope));
    }

    private RunReport record(RunReport report) {
        runs.add(report);
        out.print(report.summary());
        return report;
    }

    /**
     * Records a verification.
     *
     * @param checkName what is verified
     * @param passed    whether it held
     * @param detail    observed values
     */
    public void check(String checkName, boolean passed, String detail) {
        checks.add(new Check(checkName, passed, detail));
        out.printf("  [%s] %s - %s%n", passed ? "PASS" : "FAIL", checkName, detail);
    }

    /**
     * Final compliance scan of the logs, then writes metrics and the scenario report.
     *
     * @return the scenario result
     */
    public ScenarioResult finish() {
        List<ContentScanner.Finding> pii = ContentScanner.scan(
                List.of(audit.file(), workDir.resolve("decisions.jsonl")), ContentScanner.PII_RULES);
        check("audit and decision logs contain no PII or raw IPs", pii.isEmpty(), pii.isEmpty() ? "clean" : pii.toString());
        MetricsReport metrics = Metrics.compute(runs, audit.events());
        boolean success = checks.stream().allMatch(Check::passed);
        try {
            Metrics.write(metrics, workDir.resolve("metrics.json"));
            Files.writeString(workDir.resolve("SCENARIO_REPORT.md"), report(metrics, success));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.printf("metrics: runs=%d e2e p50=%dms p95=%dms taskSuccessRate=%.3f mttrMs=%s counters=%s%n",
                metrics.runs(), metrics.endToEndLatencyP50Ms(), metrics.endToEndLatencyP95Ms(),
                metrics.taskSuccessRate(), metrics.mttrMs(), metrics.counters());
        out.println("evidence: " + workDir);
        out.println("RESULT " + (success ? "SUCCESS" : "FAILURE"));
        return new ScenarioResult(name, success, List.copyOf(checks), metrics, workDir);
    }

    private String report(MetricsReport metrics, boolean success) {
        StringBuilder md = new StringBuilder("# Scenario: " + name + "\n\nResult: **" + (success ? "SUCCESS" : "FAILURE")
                + "**\n\n## Checks\n\n| Check | Result | Detail |\n|---|---|---|\n");
        checks.forEach(c -> md.append("| ").append(c.name()).append(" | ").append(c.passed() ? "PASS" : "FAIL")
                .append(" | ").append(c.detail().replace("|", "\\|")).append(" |\n"));
        md.append("\n## Runs\n\n```\n");
        runs.forEach(r -> md.append(r.summary()));
        md.append("```\n\n## Metrics\n\n- End-to-end latency p50/p95: ").append(metrics.endToEndLatencyP50Ms())
                .append(" / ").append(metrics.endToEndLatencyP95Ms()).append(" ms\n- Task success rate: ")
                .append(metrics.taskSuccessRate()).append("\n- MTTR: ")
                .append(metrics.mttrMs() == null ? "n/a" : metrics.mttrMs() + " ms").append("\n- Counters: ")
                .append(metrics.counters()).append("\n");
        return md.toString();
    }

    /**
     * Returns the working directory.
     *
     * @return the scenario working directory
     */
    public Path workDir() {
        return workDir;
    }

    /**
     * Returns the repository root.
     *
     * @return the repository root
     */
    public Path repoRoot() {
        return repoRoot;
    }

    /**
     * Returns the state store.
     *
     * @return the state store
     */
    public StateStore state() {
        return state;
    }

    /**
     * Returns the audit log.
     *
     * @return the audit log
     */
    public AuditLog audit() {
        return audit;
    }

    /**
     * Returns the LLM provider.
     *
     * @return the LLM provider
     */
    public LlmProvider llm() {
        return llm;
    }

    /**
     * Returns the approver.
     *
     * @return the approver
     */
    public ApprovalProvider approvals() {
        return approvals;
    }

    /**
     * Counts audit events of a type, optionally for one task.
     *
     * @param type   event type
     * @param taskId task id, or {@code null} for any
     * @return number of matching events
     */
    public long events(String type, String taskId) {
        return audit.events().stream()
                .filter(e -> e.type().equals(type) && (taskId == null || taskId.equals(e.taskId()))).count();
    }

    /**
     * Reads a file from the working directory.
     *
     * @param relative relative path
     * @return content, or an empty string if absent
     */
    public String read(String relative) {
        try {
            Path p = workDir.resolve(relative);
            return Files.exists(p) ? Files.readString(p) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        audit.close();
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
