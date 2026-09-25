package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.agentsdlc.orchestrator.tools.JavaToolchain;
import java.util.List;
import java.util.Map;

/**
 * Writes a regression test for the race and proves it is a real regression
 * test: it must <em>fail</em> against the unfixed legacy code. A test that
 * passes on buggy code cannot guard the fix, so that outcome fails the task.
 *
 * <p>Outputs: {@code test.class}, {@code fails.on.legacy}.</p>
 */
public final class RegressionTestAgent implements Agent {

    /** Creates the agent. */
    public RegressionTestAgent() {
        // Stateless.
    }

    @Override
    public void execute(TaskContext ctx) throws Exception {
        String source = ctx.llm().complete(new LlmRequest("code", "Write a regression test for the race.",
                ctx.require("triage", "component"), Map.of("template", "alias/regression-test")));
        ctx.writeArtifact(LegacyModule.TEST_ROOT + "AliasRegistryRaceTest.java", source);
        JavaToolchain.TestResult onLegacy = new LegacyModule(ctx.workDir())
                .compileAndTest(List.of(LegacyModule.REGRESSION_TEST), "classes-legacy");
        if (onLegacy.failed() == 0) {
            throw new IllegalStateException("regression test passes on the buggy code; it does not detect the race");
        }
        ctx.put("test.class", LegacyModule.REGRESSION_TEST);
        ctx.put("fails.on.legacy", "true");
        ctx.decide("regression test accepted", "it fails on legacy code: " + onLegacy.failures(),
                Map.of("failed", Long.toString(onLegacy.failed())));
    }
}
