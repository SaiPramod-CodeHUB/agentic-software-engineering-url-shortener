package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.tools.SourceIndex;
import java.nio.file.Path;
import java.util.Map;

/**
 * Inventories the existing codebase (independent of the spec), so design can
 * build on what exists. It has no dependency on requirements, which is what
 * lets re-planning preserve it when only the spec changes.
 *
 * <p>Outputs: {@code main.classes}, {@code test.classes}.</p>
 */
public final class RepoInventoryAgent implements Agent {

    private final Path repoRoot;

    /**
     * Creates the agent.
     *
     * @param repoRoot repository root to inventory
     */
    public RepoInventoryAgent(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    @Override
    public void execute(TaskContext ctx) {
        SourceIndex main = SourceIndex.scan(repoRoot, "src/main/java");
        SourceIndex test = SourceIndex.scan(repoRoot, "src/test/java");
        ctx.put("main.classes", Integer.toString(main.size()));
        ctx.put("test.classes", Integer.toString(test.size()));
        ctx.writeArtifact("REPO_INVENTORY.md", "# Repository inventory\n\n- Main classes: " + main.size()
                + "\n- Test classes: " + test.size() + "\n- Endpoints owned by the shortener: "
                + main.endpoints("ShortenerController") + "\n");
        ctx.decide("inventory taken", "design must extend the existing service rather than duplicate it",
                Map.of("mainClasses", Integer.toString(main.size())));
    }
}
