package com.agentsdlc.orchestrator.scenario;

import com.agentsdlc.orchestrator.approval.ApprovalProvider;
import com.agentsdlc.orchestrator.approval.ClarificationProvider;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.engine.RunReport;
import com.agentsdlc.orchestrator.engine.RunStatus;
import com.agentsdlc.orchestrator.llm.DeterministicLlmProvider;
import com.agentsdlc.orchestrator.llm.LlmProvider;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs <em>any</em> natural-language requirement through the full governed
 * feature pipeline: normalise → (clarify with a human if ambiguous → re-plan)
 * → design → implement → test ∥ docs → change record → release, with every
 * gate, human checkpoint, retry, rollback and audit event of the fixed
 * scenarios.
 *
 * <p>Useful output needs a hosted model ({@code AGENTIC_LLM=claude}). In
 * offline mode the deterministic provider only knows the three demo
 * features, so any other request correctly stops as a DRAFT: the system
 * refuses to build what it cannot specify.</p>
 *
 * <p>Generated code is a standalone, tested component under
 * {@code working_tree/request/generated/}; it is not merged into the
 * service. Integrating it stays a human-reviewed change.</p>
 */
public final class RequestScenario {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RequestScenario() {
    }

    /**
     * Runs the pipeline for one requirement.
     *
     * @param request   the requirement text
     * @param outRoot   parent directory for evidence
     * @param repoRoot  repository root
     * @param llm       language model
     * @param approvals human approver (console or scripted)
     * @param clarifier human answering clarifying questions
     * @param out       console
     * @return the result
     */
    public static ScenarioResult run(String request, Path outRoot, Path repoRoot, LlmProvider llm,
                                     ApprovalProvider approvals, ClarificationProvider clarifier, PrintStream out) {
        try (ScenarioEnvironment env = new ScenarioEnvironment("request", outRoot, repoRoot, approvals, llm, out)) {
            out.println("requirement: " + request);
            out.println("llm: " + llm.name());
            if (llm instanceof DeterministicLlmProvider) {
                out.println("note: offline mode only builds the three demo features. For any other requirement set "
                        + "AGENTIC_LLM=claude and ANTHROPIC_API_KEY.");
            }
            env.state().put(StateStore.HUMAN_NS, "request", request);
            NormalizedSpec plan = new RequirementNormalizer(llm).normalize(request, Map.of(), 1);
            // The incident template is specific to the alias-race demo, so live requests use the feature template.
            TaskGraph graph = Decomposer.feature(new Decomposer.Bindings(env.repoRoot(), "0.1.0", "CHG-REQ-001",
                    null, false));
            env.audit().record(null, "PLAN_CREATED", null, Map.of("kind", plan.kind(), "feature", plan.feature(),
                    "ambiguityScore", plan.ambiguityScore(), "template", "feature",
                    "waves", graph.waves().stream().map(w -> w.stream().map(TaskSpec::id).toList()).toList()));

            RunReport run = env.run("request", graph);
            if (run.status() == RunStatus.SAFE_STOPPED && "DRAFT".equals(specStatus(env))) {
                run = clarifyAndReplan(env, graph, clarifier, run, out);
            }

            env.check("spec is READY (clear enough to build)", "READY".equals(specStatus(env)),
                    "status=" + specStatus(env) + ", feature=" + env.state().get("requirements", "spec.feature")
                            .orElse("?"));
            env.check("pipeline completed", run.status() == RunStatus.SUCCEEDED,
                    run.status() + (run.haltReason() == null ? "" : ": " + run.haltReason()));
            long testsRun = Long.parseLong(env.state().get("test", "tests.run").orElse("0"));
            String failed = env.state().get("test", "tests.failed").orElse("n/a");
            env.check("generated code compiled and its generated tests passed", testsRun > 0 && "0".equals(failed),
                    "run=" + testsRun + " failed=" + failed);
            env.check("human final sign-off recorded before release",
                    env.state().get(StateStore.APPROVALS_NS, "release#final-signoff").orElse("").startsWith("APPROVED"),
                    env.state().get(StateStore.APPROVALS_NS, "release#final-signoff").orElse("none"));
            env.state().get("implement", "source.path").ifPresent(p -> out.println("generated source: "
                    + env.workDir().resolve(p)));
            return env.finish();
        }
    }

    private static RunReport clarifyAndReplan(ScenarioEnvironment env, TaskGraph graph, ClarificationProvider clarifier,
                                              RunReport first, PrintStream out) {
        Map<String, String> questions = parse(env.state().get("requirements", "spec.questions").orElse("{}"));
        if (questions.isEmpty()) {
            out.println("spec is DRAFT but has no questions to ask; stopping safely.");
            return first;
        }
        Map<String, String> answers = clarifier.answer(questions);
        env.audit().record(first.runId(), "HUMAN_CLARIFICATION", "requirements", Map.of(
                "questions", List.copyOf(new TreeMap<>(questions).keySet()),
                "answered", List.copyOf(new TreeMap<>(answers).keySet())));
        if (answers.isEmpty()) {
            out.println("no answers given; the spec stays a DRAFT and nothing is built.");
            return first;
        }
        env.state().put(StateStore.HUMAN_NS, "clarifications", write(answers));
        Replanner replanner = new Replanner(graph, env.state());
        Map<String, String> before = replanner.fingerprint();
        RunReport respec = env.run("request", graph, Set.of("requirements"));
        if (!"READY".equals(specStatus(env))) {
            out.println("spec is still a DRAFT after clarification; stopping safely.");
            return respec;
        }
        Replanner.Plan plan = replanner.replan(before, env.audit(), respec.runId());
        out.println("re-plan: changed=" + plan.changed() + " invalidated=" + plan.invalidated()
                + " preserved=" + plan.preserved());
        return env.run("request", graph, plan.invalidated());
    }

    private static String specStatus(ScenarioEnvironment env) {
        return env.state().get("requirements", "spec.status").orElse("MISSING");
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
