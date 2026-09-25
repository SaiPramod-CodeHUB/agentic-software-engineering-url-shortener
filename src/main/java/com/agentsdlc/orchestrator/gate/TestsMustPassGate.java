package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.Tags;
import com.agentsdlc.orchestrator.core.TaskSpec;

/**
 * Requires the task to have actually run tests ({@code tests.run > 0}) and
 * none to have failed. "Zero tests ran" is a failure: a green build that
 * tested nothing proves nothing.
 */
public final class TestsMustPassGate implements Gate {

    /** Creates the gate. */
    public TestsMustPassGate() {
        // Stateless.
    }

    @Override
    public String name() {
        return "tests-must-pass";
    }

    @Override
    public Phase phase() {
        return Phase.EXIT;
    }

    @Override
    public boolean appliesTo(TaskSpec task) {
        return task.hasTag(Tags.TESTS_MUST_PASS);
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        String id = ctx.task().id();
        long run = Long.parseLong(ctx.state().get(id, "tests.run").orElse("0"));
        long failed = Long.parseLong(ctx.state().get(id, "tests.failed").orElse("0"));
        if (run == 0) {
            return GateResult.block("no tests were executed");
        }
        return failed == 0
                ? GateResult.pass(run + " test(s) passed")
                : GateResult.block(failed + " of " + run + " test(s) failed");
    }
}
