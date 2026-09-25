package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import java.util.Map;

/**
 * Writes the production source for the feature under
 * {@code generated/src/main/java}.
 *
 * <p>When constructed with {@code injectCredentialOnFirstAttempt}, the first
 * attempt deliberately leaves a credential-shaped debug line in the file —
 * scripted fault injection standing in for a model that pastes a secret.
 * The secrets-scan exit gate must catch it, and the retry (which sees the
 * gate's failure message) must remove it. This is how the scenario proves
 * the gate works rather than merely exists.</p>
 *
 * <p>Outputs: {@code class.name}, {@code source.path}.</p>
 */
public final class ImplementerAgent implements Agent {

    /** Root of generated sources relative to the working directory. */
    public static final String MAIN_ROOT = "generated/src/main/java/";

    private final String specTask;
    private final boolean injectCredentialOnFirstAttempt;

    /**
     * Creates the agent.
     *
     * @param specTask                        id of the task that published the spec
     * @param injectCredentialOnFirstAttempt  simulate a leaked secret on attempt 1
     */
    public ImplementerAgent(String specTask, boolean injectCredentialOnFirstAttempt) {
        this.specTask = specTask;
        this.injectCredentialOnFirstAttempt = injectCredentialOnFirstAttempt;
    }

    @Override
    public void execute(TaskContext ctx) {
        String feature = ctx.require(specTask, "spec.feature");
        String source = ctx.llm().complete(new LlmRequest("code", "Implement the feature in Java 21.",
                ctx.require(specTask, "spec.md"), Map.of("feature", feature, "template", feature + "/main")));
        boolean flaggedBefore = ctx.lastFailure().map(f -> f.contains("secrets-scan")).orElse(false);
        if (injectCredentialOnFirstAttempt && ctx.attempt() == 1) {
            // Assembled at runtime so this repository's own source never contains the pattern.
            String fake = "sk_demo_" + "0123456789abcdef".repeat(2);
            source = source + "// debug config: api_key = \"" + fake + "\"\n";
        }
        String className = AgentSupport.qualifiedClassName(source);
        String path = MAIN_ROOT + AgentSupport.sourcePath(className);
        ctx.writeArtifact(path, source);
        ctx.put("class.name", className);
        ctx.put("source.path", path);
        if (flaggedBefore) {
            ctx.decide("regenerated source without the flagged credential",
                    "previous attempt was rejected by the secrets-scan gate", Map.of("attempt",
                            Integer.toString(ctx.attempt())));
        }
    }
}
