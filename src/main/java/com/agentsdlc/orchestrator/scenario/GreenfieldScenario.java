package com.agentsdlc.orchestrator.scenario;

import com.agentsdlc.orchestrator.approval.ScriptedApprovalProvider;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.engine.RunReport;
import com.agentsdlc.orchestrator.engine.RunStatus;
import com.agentsdlc.orchestrator.spec.Decomposer;
import com.agentsdlc.orchestrator.spec.NormalizedSpec;
import com.agentsdlc.orchestrator.spec.RequirementNormalizer;
import com.agentsdlc.orchestrator.state.StateStore;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Greenfield: a new QR-code feature taken through the full SDLC —
 * requirements → design → implement → (test ∥ docs) → change record →
 * release — with real code generation, real compilation, real JUnit
 * execution, a secrets leak caught by a gate and fixed by a retry, and human
 * approval of the change and final sign-off of the release.
 */
public final class GreenfieldScenario {

    /** The natural-language request driving the scenario. */
    public static final String REQUEST = "Add a QR code feature for short links: choose the smallest QR version "
            + "(error correction level M, byte mode, versions 1-10) that fits a short URL, reject payloads over "
            + "213 bytes, and report the module count. The code must compile and ship with passing unit tests.";

    private GreenfieldScenario() {
    }

    /**
     * Runs the scenario.
     *
     * @param outRoot  parent directory for evidence
     * @param repoRoot repository root
     * @param out      console
     * @return the result
     */
    public static ScenarioResult run(Path outRoot, Path repoRoot, PrintStream out) {
        ScriptedApprovalProvider approvals = new ScriptedApprovalProvider("release-manager", Clock.systemUTC());
        try (ScenarioEnvironment env = new ScenarioEnvironment("greenfield", outRoot, repoRoot, approvals, out)) {
            env.state().put(StateStore.HUMAN_NS, "request", REQUEST);
            NormalizedSpec plan = new RequirementNormalizer(env.llm()).normalize(REQUEST, Map.of(), 1);
            TaskGraph graph = Decomposer.decompose(plan, new Decomposer.Bindings(env.repoRoot(), "1.1.0",
                    "CHG-QR-001", null, true));
            env.audit().record(null, "PLAN_CREATED", null, Map.of("kind", plan.kind(), "feature", plan.feature(),
                    "waves", graph.waves().stream().map(w -> w.stream().map(TaskSpec::id).toList()).toList()));

            RunReport run = env.run("greenfield", graph);

            env.check("pipeline succeeded", run.status() == RunStatus.SUCCEEDED, run.status().name());
            env.check("spec was READY and targeted the QR feature", "READY".equals(
                    env.state().get("requirements", "spec.status").orElse(null))
                    && "qr-code".equals(env.state().get("requirements", "spec.feature").orElse(null)),
                    env.state().get("requirements", "spec.feature").orElse("?"));
            boolean parallelWave = graph.waves().stream().anyMatch(w -> w.stream().map(TaskSpec::id).toList()
                    .containsAll(List.of("docs", "test")));
            env.check("test and docs ran in the same parallel wave", parallelWave, graph.waves().stream()
                    .map(w -> w.stream().map(TaskSpec::id).toList()).toList().toString());
            env.check("secrets-scan gate blocked the leaked credential on attempt 1",
                    env.audit().events().stream().anyMatch(e -> e.type().equals("GATE_EVALUATED")
                            && "implement".equals(e.taskId()) && "secrets-scan".equals(e.data().get("gate"))
                            && "BLOCK".equals(e.data().get("verdict"))),
                    "implement attempts=" + run.task("implement").attempts());
            String source = env.read(env.state().get("implement", "source.path").orElse("missing"));
            env.check("retry produced clean source", run.task("implement").attempts() == 2
                    && !source.contains("api_key") && source.contains("class QrCodeSizer"),
                    "api_key present=" + source.contains("api_key"));
            long testsRun = Long.parseLong(env.state().get("test", "tests.run").orElse("0"));
            long testsFailed = Long.parseLong(env.state().get("test", "tests.failed").orElse("-1"));
            env.check("generated code compiled and its JUnit tests passed in-process",
                    testsRun >= 4 && testsFailed == 0, "run=" + testsRun + " failed=" + testsFailed);
            env.check("change record approved by a human before release",
                    env.state().get(StateStore.APPROVALS_NS, "change-record#approval").orElse("").startsWith("APPROVED")
                            && env.read("CHANGE_RECORD.md").contains("Status: APPROVED"),
                    env.state().get(StateStore.APPROVALS_NS, "change-record#approval").orElse("none"));
            env.check("final human sign-off recorded before release",
                    env.events("APPROVAL_GRANTED", "release") == 1 && env.read("RELEASE_NOTES.md").contains("Final sign-off"),
                    env.state().get(StateStore.APPROVALS_NS, "release#final-signoff").orElse("none"));
            return env.finish();
        }
    }
}
