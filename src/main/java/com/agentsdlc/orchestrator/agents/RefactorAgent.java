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
 * Behaviour-preserving refactor of the fixed registry (clearer names,
 * extracted {@code isTaken}/{@code claim} methods, explicit null checks). The
 * existing regression test is the safety net: it must still pass.
 *
 * <p>Outputs: {@code backup.registry}, {@code tests.run}, {@code tests.passed}, {@code tests.failed}.</p>
 */
public final class RefactorAgent implements Agent {

    /** Creates the agent. */
    public RefactorAgent() {
        // Stateless.
    }

    @Override
    public void execute(TaskContext ctx) throws Exception {
        LegacyModule legacy = new LegacyModule(ctx.workDir());
        ctx.put("backup.registry", legacy.read(LegacyModule.REGISTRY));
        String refactored = ctx.llm().complete(new LlmRequest("code", "Refactor for readability; keep behaviour.",
                legacy.read(LegacyModule.REGISTRY), Map.of("template", "alias/refactored")));
        ctx.writeArtifact(LegacyModule.REGISTRY, refactored);
        JavaToolchain.TestResult result = legacy.compileAndTest(List.of(LegacyModule.REGRESSION_TEST),
                "classes-refactored");
        ctx.put("tests.run", Long.toString(result.run()));
        ctx.put("tests.passed", Long.toString(result.passed()));
        ctx.put("tests.failed", Long.toString(result.failed()));
        ctx.decide("refactor accepted only if the regression suite is still green",
                "a refactor that changes behaviour is a bug, not a refactor", Map.of());
    }

    /**
     * Compensation restoring the pre-refactor source.
     *
     * @return the compensation
     */
    public static Compensation compensation() {
        return (TaskContext ctx) -> Files.writeString(ctx.workDir().resolve(LegacyModule.REGISTRY),
                ctx.require(ctx.task().id(), "backup.registry"));
    }
}
