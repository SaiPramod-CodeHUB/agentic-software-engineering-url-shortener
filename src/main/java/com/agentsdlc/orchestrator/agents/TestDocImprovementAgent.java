package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.agentsdlc.orchestrator.tools.JavaToolchain;
import java.util.List;
import java.util.Map;

/**
 * Test and documentation improvement: adds edge-case tests (raising the
 * number of behaviours covered) and corrects the stale module doc, which
 * still claimed "last write wins".
 *
 * <p>Outputs: {@code tests.before}, {@code tests.run}, {@code tests.passed},
 * {@code tests.failed}, {@code doc.fixed}.</p>
 */
public final class TestDocImprovementAgent implements Agent {

    /** Sentence in the legacy doc that no longer matches behaviour. */
    public static final String STALE_SENTENCE = "Registering an alias that already exists replaces its target "
            + "(last write wins).";
    /** Corrected sentence. */
    public static final String CORRECTED_SENTENCE = "The first registration of an alias wins; later registrations "
            + "of the same alias are refused (register returns false) and never change its target.";

    /** Creates the agent. */
    public TestDocImprovementAgent() {
        // Stateless.
    }

    @Override
    public void execute(TaskContext ctx) throws Exception {
        LegacyModule legacy = new LegacyModule(ctx.workDir());
        String edgeTests = ctx.llm().complete(new LlmRequest("code", "Add edge-case tests.",
                legacy.read(LegacyModule.REGISTRY), Map.of("template", "alias/edge-test")));
        ctx.writeArtifact(LegacyModule.TEST_ROOT + "AliasRegistryEdgeCaseTest.java", edgeTests);

        String doc = legacy.read(LegacyModule.DOC);
        boolean stale = doc.contains(STALE_SENTENCE);
        ctx.writeArtifact(LegacyModule.DOC, doc.replace(STALE_SENTENCE, CORRECTED_SENTENCE));

        JavaToolchain.TestResult result = legacy.compileAndTest(
                List.of(LegacyModule.REGRESSION_TEST, LegacyModule.EDGE_TEST), "classes-improved");
        ctx.put("tests.before", "1");
        ctx.put("tests.run", Long.toString(result.run()));
        ctx.put("tests.passed", Long.toString(result.passed()));
        ctx.put("tests.failed", Long.toString(result.failed()));
        ctx.put("doc.fixed", Boolean.toString(stale));
        ctx.decide("tests 1 -> " + result.run() + ", stale doc " + (stale ? "corrected" : "already correct"),
                "docs that contradict behaviour cause the next incident", Map.of());
    }
}
