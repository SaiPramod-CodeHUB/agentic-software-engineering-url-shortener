package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import java.util.Map;

/**
 * Writes user-facing documentation for the feature. Runs in parallel with
 * testing: both depend only on the implementation.
 *
 * <p>Outputs: {@code docs.path}.</p>
 */
public final class DocsAgent implements Agent {

    private final String specTask;
    private final String implementTask;

    /**
     * Creates the agent.
     *
     * @param specTask      id of the task that published the spec
     * @param implementTask id of the task that wrote the production source
     */
    public DocsAgent(String specTask, String implementTask) {
        this.specTask = specTask;
        this.implementTask = implementTask;
    }

    @Override
    public void execute(TaskContext ctx) {
        String feature = ctx.require(specTask, "spec.feature");
        String context = "Implemented by `" + ctx.require(implementTask, "class.name") + "`.\n\n"
                + ctx.require(specTask, "spec.md");
        String docs = ctx.llm().complete(new LlmRequest("docs", "Write user documentation.", context,
                Map.of("feature", feature)));
        ctx.writeArtifact("FEATURE_README.md", docs);
        ctx.put("docs.path", "FEATURE_README.md");
    }
}
