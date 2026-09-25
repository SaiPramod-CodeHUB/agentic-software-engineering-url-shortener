package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.Compensation;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.agentsdlc.orchestrator.tools.JavaToolchain;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Applies the fix (atomic {@code putIfAbsent} claim) and the matching schema
 * migration (unique index), then re-runs the regression test, which must now
 * pass. The original source is kept in state so the compensation can restore
 * it byte-for-byte.
 *
 * <p>Outputs: {@code backup.registry}, {@code migration.path}, {@code tests.run},
 * {@code tests.passed}, {@code tests.failed}.</p>
 */
public final class MigrateFixAgent implements Agent {

    /** Migration script written next to the legacy module. */
    public static final String MIGRATION = "legacy/db/V2__unique_alias_index.sql";

    /** Creates the agent. */
    public MigrateFixAgent() {
        // Stateless.
    }

    @Override
    public void execute(TaskContext ctx) throws Exception {
        LegacyModule legacy = new LegacyModule(ctx.workDir());
        ctx.put("backup.registry", legacy.read(LegacyModule.REGISTRY));
        String fixed = ctx.llm().complete(new LlmRequest("code", "Fix the race without changing the API.",
                ctx.require("triage", "component"), Map.of("template", "alias/fixed")));
        ctx.writeArtifact(LegacyModule.REGISTRY, fixed);
        ctx.writeArtifact(MIGRATION, """
                -- Enforce alias uniqueness in the database as well: the in-process fix protects one node,
                -- the unique index protects every node and every writer.
                CREATE UNIQUE INDEX IF NOT EXISTS ux_aliases_alias ON aliases (alias);
                """);
        ctx.put("migration.path", MIGRATION);
        JavaToolchain.TestResult result = legacy.compileAndTest(List.of(LegacyModule.REGRESSION_TEST), "classes-fixed");
        ctx.put("tests.run", Long.toString(result.run()));
        ctx.put("tests.passed", Long.toString(result.passed()));
        ctx.put("tests.failed", Long.toString(result.failed()));
        ctx.decide("replace check-then-act with putIfAbsent + unique index",
                "atomic claim gives exactly one winner in-process; the index enforces it across nodes",
                Map.of("regressionFailed", Long.toString(result.failed())));
    }

    /**
     * Compensation restoring the original registry source and removing the migration.
     *
     * @return the compensation
     */
    public static Compensation compensation() {
        return (TaskContext ctx) -> {
            String original = ctx.require(ctx.task().id(), "backup.registry");
            Files.writeString(ctx.workDir().resolve(LegacyModule.REGISTRY), original);
            Files.deleteIfExists(ctx.workDir().resolve(MIGRATION));
        };
    }
}
