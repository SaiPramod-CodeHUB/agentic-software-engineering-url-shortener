package com.agentsdlc.orchestrator.spec;

import com.agentsdlc.orchestrator.agents.ChangeRecordAgent;
import com.agentsdlc.orchestrator.agents.DesignAgent;
import com.agentsdlc.orchestrator.agents.DocsAgent;
import com.agentsdlc.orchestrator.agents.ImpactAnalysisAgent;
import com.agentsdlc.orchestrator.agents.ImplementerAgent;
import com.agentsdlc.orchestrator.agents.MigrateFixAgent;
import com.agentsdlc.orchestrator.agents.RefactorAgent;
import com.agentsdlc.orchestrator.agents.RegressionTestAgent;
import com.agentsdlc.orchestrator.agents.ReleaseAgent;
import com.agentsdlc.orchestrator.agents.RepoInventoryAgent;
import com.agentsdlc.orchestrator.agents.ReproduceAgent;
import com.agentsdlc.orchestrator.agents.RequirementsAgent;
import com.agentsdlc.orchestrator.agents.TesterAgent;
import com.agentsdlc.orchestrator.agents.TestDocImprovementAgent;
import com.agentsdlc.orchestrator.agents.TriageAgent;
import com.agentsdlc.orchestrator.core.RetryPolicy;
import com.agentsdlc.orchestrator.core.RiskTier;
import com.agentsdlc.orchestrator.core.Tags;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Decomposes a normalised spec into a task DAG. The spec's {@code kind}
 * selects a pipeline template; each template fixes the dependencies, risk
 * tiers, retry policies, compensations, output contracts and policy tags of
 * its tasks, so every pipeline of a kind is governed identically.
 */
public final class Decomposer {

    /** Id of the task that publishes the spec (read by the spec-ready gate). */
    public static final String SPEC_TASK = "requirements";
    /** Package directory of the product (the URL shortener) that incident pipelines change. */
    public static final String PRODUCT_PACKAGE = "com/agentsdlc/shortener";
    /** Id of the change-record task (read by the change-control gate). */
    public static final String CHANGE_TASK = "change-record";

    /**
     * Scenario-specific parameters bound into the template's agents.
     *
     * @param repoRoot                       repository analysed by inventory/impact analysis
     * @param version                        version the release task publishes
     * @param changeId                       change record id
     * @param incidentId                     incident id (incident pipelines only)
     * @param injectCredentialOnFirstAttempt scripted secret leak on the first implement attempt
     */
    public record Bindings(Path repoRoot, String version, String changeId, String incidentId,
                           boolean injectCredentialOnFirstAttempt) {
    }

    private Decomposer() {
    }

    /**
     * Builds the DAG for a spec.
     *
     * @param spec     the normalised spec
     * @param bindings scenario parameters
     * @return the validated task graph
     */
    public static TaskGraph decompose(NormalizedSpec spec, Bindings bindings) {
        return "INCIDENT".equals(spec.kind()) ? incident(bindings) : feature(bindings);
    }

    /**
     * Feature pipeline: requirements ∥ inventory → design → implement →
     * (test ∥ docs) → change record → release.
     *
     * @param b scenario parameters
     * @return the graph
     */
    public static TaskGraph feature(Bindings b) {
        return new TaskGraph(List.of(
                TaskSpec.builder(SPEC_TASK, new RequirementsAgent()).stage("requirements")
                        .requires("spec.status", "spec.md").build(),
                TaskSpec.builder("repo-inventory", new RepoInventoryAgent(b.repoRoot())).stage("analysis")
                        .requires("main.classes").build(),
                TaskSpec.builder("design", new DesignAgent(SPEC_TASK)).stage("design")
                        .dependsOn(SPEC_TASK, "repo-inventory").tags(Tags.REQUIRES_READY_SPEC)
                        .requires("design.md").build(),
                TaskSpec.builder("implement", new ImplementerAgent(SPEC_TASK, b.injectCredentialOnFirstAttempt()))
                        .stage("implement").dependsOn("design").risk(RiskTier.MEDIUM)
                        .retry(RetryPolicy.exponential(3, Duration.ofMillis(10)))
                        .tags(Tags.REQUIRES_READY_SPEC).requires("class.name", "source.path")
                        .compensation(ctx -> Files.deleteIfExists(ctx.workDir().resolve(
                                ctx.require(ctx.task().id(), "source.path"))))
                        .build(),
                TaskSpec.builder("test", new TesterAgent(SPEC_TASK, "implement")).stage("test")
                        .dependsOn("implement").risk(RiskTier.MEDIUM)
                        .retry(RetryPolicy.exponential(2, Duration.ofMillis(10)))
                        .tags(Tags.TESTS_MUST_PASS).requires("tests.run", "tests.failed").build(),
                TaskSpec.builder("docs", new DocsAgent(SPEC_TASK, "implement")).stage("docs")
                        .dependsOn("implement").requires("docs.path").build(),
                TaskSpec.builder(CHANGE_TASK, new ChangeRecordAgent(b.changeId(), List.of("test")))
                        .stage("change").dependsOn("test", "docs").risk(RiskTier.MEDIUM)
                        .tags(Tags.NEEDS_APPROVAL).requires("status", "change.id").build(),
                TaskSpec.builder("release", new ReleaseAgent(b.version(), CHANGE_TASK)).stage("release")
                        .dependsOn(CHANGE_TASK).risk(RiskTier.HIGH).tags(Tags.RELEASE).requires("version").build()));
    }

    /**
     * Incident pipeline: requirements → triage → impact analysis → reproduce
     * → regression test → migrate/fix → refactor → test/doc improvement →
     * change record → release.
     *
     * @param b scenario parameters
     * @return the graph
     */
    public static TaskGraph incident(Bindings b) {
        return new TaskGraph(List.of(
                TaskSpec.builder(SPEC_TASK, new RequirementsAgent()).stage("requirements")
                        .requires("spec.status", "spec.md").build(),
                TaskSpec.builder("triage", new TriageAgent(b.incidentId())).stage("triage")
                        .dependsOn(SPEC_TASK).requires("incident.id", "keywords").build(),
                TaskSpec.builder("impact-analysis", new ImpactAnalysisAgent(b.repoRoot(), PRODUCT_PACKAGE))
                        .stage("analysis")
                        .dependsOn("triage").requires("classes", "endpoints", "tables").build(),
                TaskSpec.builder("reproduce", new ReproduceAgent()).stage("reproduce")
                        .dependsOn("impact-analysis").requires("reproduced").build(),
                TaskSpec.builder("regression-test", new RegressionTestAgent()).stage("test")
                        .dependsOn("reproduce").risk(RiskTier.MEDIUM).requires("fails.on.legacy").build(),
                TaskSpec.builder("migrate-fix", new MigrateFixAgent()).stage("implement")
                        .dependsOn("regression-test").risk(RiskTier.HIGH).destructive()
                        .retry(RetryPolicy.exponential(2, Duration.ofMillis(10)))
                        .compensation(MigrateFixAgent.compensation())
                        .tags(Tags.REQUIRES_IMPACT_ANALYSIS, Tags.TESTS_MUST_PASS)
                        .requires("backup.registry", "migration.path").build(),
                TaskSpec.builder("refactor", new RefactorAgent()).stage("refactor")
                        .dependsOn("migrate-fix").risk(RiskTier.MEDIUM).compensation(RefactorAgent.compensation())
                        .tags(Tags.REQUIRES_IMPACT_ANALYSIS, Tags.TESTS_MUST_PASS).build(),
                TaskSpec.builder("improve-tests-docs", new TestDocImprovementAgent()).stage("test")
                        .dependsOn("refactor").risk(RiskTier.MEDIUM).tags(Tags.TESTS_MUST_PASS)
                        .requires("doc.fixed").build(),
                TaskSpec.builder(CHANGE_TASK, new ChangeRecordAgent(b.changeId(),
                                List.of("migrate-fix", "refactor", "improve-tests-docs")))
                        .stage("change").dependsOn("improve-tests-docs").risk(RiskTier.MEDIUM)
                        .tags(Tags.NEEDS_APPROVAL).requires("status", "change.id").build(),
                TaskSpec.builder("release", new ReleaseAgent(b.version(), CHANGE_TASK)).stage("release")
                        .dependsOn(CHANGE_TASK).risk(RiskTier.HIGH).tags(Tags.RELEASE).requires("version").build()));
    }
}
