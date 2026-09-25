package com.agentsdlc.orchestrator.scenario;

import com.agentsdlc.orchestrator.approval.ScriptedApprovalProvider;
import com.agentsdlc.orchestrator.approval.ScriptedClarificationProvider;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskStatus;
import com.agentsdlc.orchestrator.engine.RunReport;
import com.agentsdlc.orchestrator.engine.RunStatus;
import com.agentsdlc.orchestrator.replan.Replanner;
import com.agentsdlc.orchestrator.spec.Decomposer;
import com.agentsdlc.orchestrator.spec.NormalizedSpec;
import com.agentsdlc.orchestrator.spec.RequirementNormalizer;
import com.agentsdlc.orchestrator.state.StateStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ambiguous request: "make links smarter".
 * <ol>
 *   <li>Run 1: the spec is scored ambiguous and published as
 *       {@code DRAFT (DO NOT BUILD)} with clarifying questions; the
 *       spec-ready gate blocks design and the run safe-stops.</li>
 *   <li>A human answers the questions once (scripted); the answers are
 *       stored as human input.</li>
 *   <li>Run 2: only requirements re-runs, producing spec v2 (READY).</li>
 *   <li>The re-planner sees the requirements output hash change and
 *       invalidates exactly its downstream closure; the independent repo
 *       inventory is preserved.</li>
 *   <li>Run 3: only the invalidated tasks run, building the clarified scope
 *       (idle-link expiry) without asking the human again.</li>
 * </ol>
 */
public final class AmbiguousScenario {

    /** The deliberately vague request. */
    public static final String REQUEST = "make links smarter";

    /** Scripted human answers, by question id. */
    public static final Map<String, String> ANSWERS = Map.of(
            "q1", "Auto-expire links that have been idle (no clicks) for too long.",
            "q2", "A link with no clicks for 30 days must return 410 Gone; any click resets the timer.",
            "q3", "Store no new personal data; links never clicked count from their creation time.");

    private static final ObjectMapper JSON = new ObjectMapper();

    private AmbiguousScenario() {
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
        ScriptedApprovalProvider approvals = new ScriptedApprovalProvider("product-owner", Clock.systemUTC());
        ScriptedClarificationProvider human = new ScriptedClarificationProvider(ANSWERS);
        try (ScenarioEnvironment env = new ScenarioEnvironment("ambiguous", outRoot, repoRoot, approvals, out)) {
            env.state().put(StateStore.HUMAN_NS, "request", REQUEST);
            NormalizedSpec plan = new RequirementNormalizer(env.llm()).normalize(REQUEST, Map.of(), 1);
            TaskGraph graph = Decomposer.decompose(plan, new Decomposer.Bindings(env.repoRoot(), "1.2.0",
                    "CHG-IDLE-007", null, false));
            env.audit().record(null, "PLAN_CREATED", null, Map.of("kind", plan.kind(), "feature", plan.feature(),
                    "ambiguityScore", plan.ambiguityScore()));

            RunReport first = env.run("ambiguous", graph);
            String draft = env.read("SPEC_v1_DRAFT.md");
            env.check("vague request scored ambiguous and published as a DRAFT",
                    plan.status() == NormalizedSpec.Status.DRAFT && draft.contains("DRAFT (DO NOT BUILD)")
                            && draft.contains("Clarifying questions"), "ambiguityScore=" + plan.ambiguityScore());
            env.check("spec-ready gate blocked design; run safe-stopped with downstream SKIPPED",
                    first.status() == RunStatus.SAFE_STOPPED && first.task("design").status() == TaskStatus.BLOCKED
                            && first.task("implement").status() == TaskStatus.SKIPPED
                            && first.task("release").status() == TaskStatus.SKIPPED, first.haltReason());

            Map<String, String> questions = parse(env.state().get("requirements", "spec.questions").orElse("{}"));
            Map<String, String> answers = human.answer(questions);
            env.state().put(StateStore.HUMAN_NS, "clarifications", write(answers));
            env.audit().record(first.runId(), "HUMAN_CLARIFICATION", "requirements", Map.of(
                    "questions", List.copyOf(new TreeMap<>(questions).keySet()),
                    "answered", List.copyOf(new TreeMap<>(answers).keySet()), "by", "product-owner"));

            Replanner replanner = new Replanner(graph, env.state());
            Map<String, String> before = replanner.fingerprint();
            RunReport second = env.run("ambiguous", graph, Set.of("requirements"));
            Replanner.Plan replan = replanner.replan(before, env.audit(), second.runId());
            env.check("clarified spec v2 is READY", "READY".equals(env.state().get("requirements", "spec.status")
                    .orElse(null)) && env.read("SPEC_v2.md").contains("Human clarifications"),
                    "feature=" + env.state().get("requirements", "spec.feature").orElse("?"));
            env.check("re-planner invalidated exactly the downstream closure of requirements",
                    replan.changed().equals(Set.of("requirements"))
                            && replan.invalidated().equals(Set.of("design", "implement", "test", "docs",
                            "change-record", "release"))
                            && replan.preserved().equals(Set.of("repo-inventory")), replan.toString());

            RunReport third = env.run("ambiguous", graph, replan.invalidated());
            env.check("clarified scope built and released", third.status() == RunStatus.SUCCEEDED
                    && third.task("repo-inventory").status() == TaskStatus.REUSED
                    && third.task("requirements").status() == TaskStatus.REUSED, third.status().name());
            env.check("generated idle-expiry code passed its tests",
                    "generated.expiry.IdleExpiryPolicy".equals(env.state().get("implement", "class.name").orElse(null))
                            && "0".equals(env.state().get("test", "tests.failed").orElse(null))
                            && Long.parseLong(env.state().get("test", "tests.run").orElse("0")) >= 3,
                    "tests.run=" + env.state().get("test", "tests.run").orElse("?"));
            env.check("human asked exactly once; preserved work not redone",
                    human.calls() == 1 && env.events("TASK_ATTEMPT", "repo-inventory") == 1,
                    "clarification calls=" + human.calls() + ", repo-inventory attempts="
                            + env.events("TASK_ATTEMPT", "repo-inventory"));
            env.check("final human sign-off before release", env.events("APPROVAL_GRANTED", "release") == 1,
                    env.state().get(StateStore.APPROVALS_NS, "release#final-signoff").orElse("none"));
            return env.finish();
        }
    }

    private static Map<String, String> parse(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("spec.questions is not a JSON object", e);
        }
    }

    private static String write(Map<String, String> map) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(map));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
