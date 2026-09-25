package com.agentsdlc.orchestrator.gate;

import com.agentsdlc.orchestrator.core.Tags;
import com.agentsdlc.orchestrator.core.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Brownfield guardrail: no change to existing code before someone has
 * written down what it touches. Requires {@code IMPACT_ANALYSIS.md} with all
 * four sections (classes, endpoints, tables, call paths).
 */
public final class ImpactAnalysisGate implements Gate {

    /** Artifact the gate looks for, relative to the working directory. */
    public static final String REPORT = "IMPACT_ANALYSIS.md";
    /** Sections the report must contain. */
    public static final List<String> SECTIONS =
            List.of("## Affected classes", "## Endpoints", "## Tables", "## Call paths");

    /** Creates the gate. */
    public ImpactAnalysisGate() {
        // Stateless.
    }

    @Override
    public String name() {
        return "impact-analysis";
    }

    @Override
    public Phase phase() {
        return Phase.ENTRY;
    }

    @Override
    public boolean appliesTo(TaskSpec task) {
        return task.hasTag(Tags.REQUIRES_IMPACT_ANALYSIS);
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        Path report = ctx.workDir().resolve(REPORT);
        if (!Files.isRegularFile(report)) {
            return GateResult.block(REPORT + " is missing");
        }
        try {
            String text = Files.readString(report);
            List<String> missing = SECTIONS.stream().filter(s -> !text.contains(s)).toList();
            return missing.isEmpty()
                    ? GateResult.pass(REPORT + " present with all sections")
                    : GateResult.block(REPORT + " lacks sections " + missing);
        } catch (IOException e) {
            return GateResult.block("cannot read " + REPORT + ": " + e.getMessage());
        }
    }
}
