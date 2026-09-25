package com.agentsdlc.orchestrator.scenario;

import com.agentsdlc.orchestrator.agents.LegacyModule;
import com.agentsdlc.orchestrator.agents.TestDocImprovementAgent;
import com.agentsdlc.orchestrator.approval.ScriptedApprovalProvider;
import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.RetryPolicy;
import com.agentsdlc.orchestrator.core.RiskTier;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.core.TaskStatus;
import com.agentsdlc.orchestrator.engine.RunReport;
import com.agentsdlc.orchestrator.engine.RunStatus;
import com.agentsdlc.orchestrator.llm.CodeTemplates;
import com.agentsdlc.orchestrator.spec.Decomposer;
import com.agentsdlc.orchestrator.spec.NormalizedSpec;
import com.agentsdlc.orchestrator.spec.RequirementNormalizer;
import com.agentsdlc.orchestrator.state.StateStore;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Brownfield: an incident in existing code, in three parts.
 * <ol>
 *   <li>Incident pipeline for the custom-alias race: triage → impact analysis
 *       of the real repository → reproduction with fault injection →
 *       regression test (proven to fail on legacy code) → migrate/fix →
 *       refactor → test/doc improvement → change record → release.</li>
 *   <li>Guardrails: a destructive task declared LOW risk is hard-blocked, and
 *       a human rejection safe-stops the run with downstream work SKIPPED.</li>
 *   <li>Rollback: a data backfill whose canary deploy keeps failing is
 *       compensated in reverse completion order and safe-stopped; a clean
 *       recovery run then succeeds and closes the incident (MTTR).</li>
 * </ol>
 */
public final class BrownfieldScenario {

    /** The incident as reported by support. */
    public static final String INCIDENT_REPORT = "Customers report duplicate custom alias registrations: two "
            + "customers were both told their alias 'promo' was created, and one alias was overwritten so it now "
            + "redirects to the other customer's link (wrong destination). Suspected race in alias registration.";

    private static final String DB = "rollback-demo/db.properties";

    private BrownfieldScenario() {
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
        ScriptedApprovalProvider approvals = new ScriptedApprovalProvider("oncall-lead", Clock.systemUTC())
                .reject("purge-orphan-clicks", "approval", "purge would delete analytics still under retention");
        try (ScenarioEnvironment env = new ScenarioEnvironment("brownfield", outRoot, repoRoot, approvals, out)) {
            incidentPipeline(env);
            guardrails(env);
            rollbackAndRecovery(env);
            return env.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void incidentPipeline(ScenarioEnvironment env) throws IOException {
        seedLegacyModule(env.workDir());
        env.state().put(StateStore.HUMAN_NS, "request", INCIDENT_REPORT);
        env.state().put(StateStore.HUMAN_NS, "incident-report", INCIDENT_REPORT);
        NormalizedSpec plan = new RequirementNormalizer(env.llm()).normalize(INCIDENT_REPORT, Map.of(), 1);
        TaskGraph graph = Decomposer.decompose(plan, new Decomposer.Bindings(env.repoRoot(), "1.0.1",
                "CHG-ALIAS-042", "INC-ALIAS-RACE", false));
        env.audit().record(null, "PLAN_CREATED", null, Map.of("kind", plan.kind(), "feature", plan.feature()));

        RunReport run = env.run("alias-incident", graph);
        env.check("incident pipeline succeeded", run.status() == RunStatus.SUCCEEDED, run.status().name());
        env.check("spec classified as INCIDENT", "INCIDENT".equals(plan.kind()), plan.kind() + "/" + plan.feature());

        String impact = env.read("IMPACT_ANALYSIS.md");
        boolean impactOk = impact.contains("ShortenerController") && impact.contains("LinkService")
                && impact.contains("POST /shorten") && impact.contains("links (Link)")
                && impact.contains("ShortenerController -> LinkService");
        String classes = env.state().get("impact-analysis", "classes").orElse("[]");
        int classCount = classes.equals("[]") ? 0 : classes.split(",").length;
        env.check("impact analysis of the real repo lists classes, endpoint, table and call path", impactOk,
                "endpoints=" + env.state().get("impact-analysis", "endpoints").orElse("[]"));
        env.check("blast radius is scoped to the product (no orchestration tooling, bounded size)",
                classCount > 0 && classCount <= 20 && !classes.contains("Agent") && !classes.contains("Orchestrator"),
                classCount + " classes: " + classes);
        env.check("impact-analysis gate passed before migrate/fix", env.audit().events().stream().anyMatch(e ->
                        "migrate-fix".equals(e.taskId()) && "impact-analysis".equals(e.data().get("gate"))
                                && "PASS".equals(e.data().get("verdict"))), "gate audited");
        env.check("race reproduced with fault injection on legacy code",
                "true".equals(env.state().get("reproduce", "reproduced").orElse(null)),
                "winners=" + env.state().get("reproduce", "winners").orElse("?"));
        env.check("regression test fails on legacy code (it detects the bug)",
                "true".equals(env.state().get("regression-test", "fails.on.legacy").orElse(null)), "fails.on.legacy=true");
        env.check("regression test passes after migrate/fix", "0".equals(env.state().get("migrate-fix",
                "tests.failed").orElse(null)) && "1".equals(env.state().get("migrate-fix", "tests.run").orElse(null)),
                "run=" + env.state().get("migrate-fix", "tests.run").orElse("?"));
        env.check("HIGH-risk destructive migrate/fix was approved by a human",
                env.state().get(StateStore.APPROVALS_NS, "migrate-fix#approval").orElse("").startsWith("APPROVED"),
                env.state().get(StateStore.APPROVALS_NS, "migrate-fix#approval").orElse("none"));
        env.check("refactor kept the regression suite green", "0".equals(env.state().get("refactor", "tests.failed")
                .orElse(null)) && env.read(LegacyModule.REGISTRY).contains("private boolean claim("), "refactored");
        env.check("coverage raised and stale doc corrected",
                "5".equals(env.state().get("improve-tests-docs", "tests.run").orElse(null))
                        && env.read(LegacyModule.DOC).contains(TestDocImprovementAgent.CORRECTED_SENTENCE),
                "tests 1 -> " + env.state().get("improve-tests-docs", "tests.run").orElse("?"));
        env.check("final human sign-off before release", env.events("APPROVAL_GRANTED", "release") == 1,
                env.state().get(StateStore.APPROVALS_NS, "release#final-signoff").orElse("none"));
        env.check("incident INC-ALIAS-RACE opened by triage and resolved by release",
                env.events("INCIDENT_OPENED", "triage") == 1 && env.events("INCIDENT_RESOLVED", "release") == 1,
                "opened+resolved");
    }

    private static void guardrails(ScenarioEnvironment env) {
        TaskGraph misclassified = new TaskGraph(List.of(
                TaskSpec.builder("drop-legacy-table", ctx -> ctx.put("dropped", "true")).stage("maintenance")
                        .risk(RiskTier.LOW).destructive().build(),
                TaskSpec.builder("reindex", ctx -> ctx.put("done", "true")).stage("maintenance")
                        .dependsOn("drop-legacy-table").build()));
        RunReport blocked = env.run("guardrail-policy", misclassified);
        env.check("destructive task declared LOW risk is hard-blocked at entry",
                blocked.status() == RunStatus.SAFE_STOPPED
                        && blocked.task("drop-legacy-table").status() == TaskStatus.BLOCKED
                        && blocked.task("reindex").status() == TaskStatus.SKIPPED
                        && env.state().get("drop-legacy-table", "dropped").isEmpty(),
                blocked.task("drop-legacy-table").reason());

        TaskGraph purge = new TaskGraph(List.of(
                TaskSpec.builder("snapshot-clicks", ctx -> ctx.put("snapshot", "ok")).stage("maintenance").build(),
                TaskSpec.builder("purge-orphan-clicks", ctx -> ctx.put("purged", "true")).stage("maintenance")
                        .dependsOn("snapshot-clicks").risk(RiskTier.HIGH).destructive().build(),
                TaskSpec.builder("vacuum", ctx -> ctx.put("done", "true")).stage("maintenance")
                        .dependsOn("purge-orphan-clicks").build()));
        RunReport rejected = env.run("guardrail-approval", purge);
        env.check("human rejection safe-stops the run; downstream marked SKIPPED (not failed)",
                rejected.status() == RunStatus.SAFE_STOPPED
                        && rejected.task("snapshot-clicks").status() == TaskStatus.SUCCEEDED
                        && rejected.task("purge-orphan-clicks").status() == TaskStatus.REJECTED
                        && rejected.task("vacuum").status() == TaskStatus.SKIPPED
                        && env.events("APPROVAL_REJECTED", "purge-orphan-clicks") == 1,
                rejected.task("purge-orphan-clicks").reason());
    }

    private static void rollbackAndRecovery(ScenarioEnvironment env) throws IOException {
        Path db = env.workDir().resolve(DB);
        Files.createDirectories(db.getParent());
        Map<String, String> original = new TreeMap<>(Map.of("schema_version", "1", "row.1", "docs", "row.2", "promo"));
        writeDb(db, original);
        AtomicBoolean canaryFails = new AtomicBoolean(true);
        TaskGraph graph = backfillPipeline(db, canaryFails);

        RunReport failed = env.run("alias-backfill", graph);
        List<String> order = env.audit().events().stream()
                .filter(e -> e.type().equals("ROLLBACK_STARTED") && e.runId().equals(failed.runId()))
                .map(e -> String.valueOf(e.data().get("reverseCompletionOrder"))).toList();
        List<AuditLog.Event> retries = env.audit().events().stream()
                .filter(e -> e.type().equals("TASK_RETRY") && "deploy-canary".equals(e.taskId())).toList();
        env.check("canary retried with exponential backoff before giving up",
                failed.task("deploy-canary").attempts() == 3 && retries.size() == 2
                        && ((Number) retries.get(0).data().get("backoffMs")).longValue() == 10
                        && ((Number) retries.get(1).data().get("backoffMs")).longValue() == 20,
                "backoffs=" + retries.stream().map(e -> e.data().get("backoffMs")).toList());
        env.check("failure rolled back in reverse completion order",
                failed.status() == RunStatus.ROLLED_BACK
                        && order.equals(List.of("[backfill-aliases, apply-migration, backup-db]"))
                        && failed.task("backfill-aliases").status() == TaskStatus.COMPENSATED
                        && failed.task("apply-migration").status() == TaskStatus.COMPENSATED,
                "order=" + order);
        env.check("safe-stop marked downstream verify-canary SKIPPED",
                failed.task("verify-canary").status() == TaskStatus.SKIPPED && env.events("SAFE_STOP", null) >= 1,
                failed.task("verify-canary").reason());
        env.check("database restored byte-for-byte to its pre-run state", readDb(db).equals(original),
                readDb(db).toString());
        env.check("snapshot-once: backfill namespace restored to its pre-first-attempt state (not the partial retry state)",
                failed.task("backfill-aliases").attempts() == 2 && env.state().snapshot("backfill-aliases").isEmpty(),
                "attempts=" + failed.task("backfill-aliases").attempts());

        canaryFails.set(false);
        RunReport recovered = env.run("alias-backfill", graph);
        env.check("recovery run succeeded and applied the migration",
                recovered.status() == RunStatus.SUCCEEDED && "2".equals(readDb(db).get("schema_version"))
                        && readDb(db).containsKey("row.3"), readDb(db).toString());
        env.check("pipeline incident opened on failure and resolved on recovery (MTTR)",
                env.audit().events().stream().anyMatch(e -> e.type().equals("INCIDENT_RESOLVED")
                        && "alias-backfill".equals(e.data().get("pipeline"))), "MTTR computed in metrics.json");
    }

    private static TaskGraph backfillPipeline(Path db, AtomicBoolean canaryFails) {
        return new TaskGraph(List.of(
                TaskSpec.builder("backup-db", ctx -> {
                    Files.copy(db, db.resolveSibling("db.backup"), StandardCopyOption.REPLACE_EXISTING);
                    ctx.put("backup", "db.backup");
                }).stage("backup").build(),
                TaskSpec.builder("apply-migration", ctx -> {
                    Map<String, String> rows = readDb(db);
                    ctx.put("previous.schema", rows.get("schema_version"));
                    rows.put("schema_version", "2");
                    writeDb(db, rows);
                }).stage("migrate").dependsOn("backup-db").risk(RiskTier.HIGH).destructive()
                        .compensation(ctx -> {
                            Map<String, String> rows = readDb(db);
                            rows.put("schema_version", ctx.require(ctx.task().id(), "previous.schema"));
                            writeDb(db, rows);
                        }).build(),
                TaskSpec.builder("backfill-aliases", ctx -> {
                    Map<String, String> rows = readDb(db);
                    rows.put("row.3", "sale");
                    writeDb(db, rows);
                    ctx.put("rows.added", "row.3");
                    if (ctx.attempt() == 1) {
                        // Transient failure after a partial write: the retry must not re-baseline on it.
                        throw new IllegalStateException("connection reset during backfill");
                    }
                    rows.put("row.4", "launch");
                    writeDb(db, rows);
                    ctx.put("rows.added", "row.3,row.4");
                }).stage("migrate").dependsOn("apply-migration").risk(RiskTier.MEDIUM)
                        .retry(RetryPolicy.exponential(2, Duration.ofMillis(10)))
                        .compensation(BrownfieldScenario::removeBackfilledRows).build(),
                TaskSpec.builder("deploy-canary", ctx -> {
                    if (canaryFails.get()) {
                        throw new IllegalStateException("canary health check failed: 5xx rate 12%");
                    }
                    ctx.put("canary", "healthy");
                }).stage("deploy").dependsOn("backfill-aliases").risk(RiskTier.MEDIUM)
                        .retry(RetryPolicy.exponential(3, Duration.ofMillis(10))).build(),
                TaskSpec.builder("verify-canary", ctx -> ctx.put("verified", "true")).stage("deploy")
                        .dependsOn("deploy-canary").build()));
    }

    private static void removeBackfilledRows(TaskContext ctx) {
        Path db = ctx.workDir().resolve(DB);
        Map<String, String> rows = readDb(db);
        for (String row : ctx.require(ctx.task().id(), "rows.added").split(",")) {
            rows.remove(row);
        }
        writeDb(db, rows);
    }

    private static void seedLegacyModule(Path workDir) throws IOException {
        Path registry = workDir.resolve(LegacyModule.REGISTRY);
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, CodeTemplates.get("alias/legacy"));
        Path doc = workDir.resolve(LegacyModule.DOC);
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, "# Aliases\n\nCustom aliases map a memorable name to a short link.\n\n"
                + TestDocImprovementAgent.STALE_SENTENCE + "\n");
    }

    static Map<String, String> readDb(Path db) {
        try {
            Map<String, String> rows = new TreeMap<>();
            for (String line : Files.readAllLines(db)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    rows.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeDb(Path db, Map<String, String> rows) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(rows).forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        try {
            Files.writeString(db, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
