package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.gate.ImpactAnalysisGate;
import com.agentsdlc.orchestrator.tools.SourceIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Brownfield codebase reasoning: scans the real repository for the concepts
 * triage identified and writes {@code IMPACT_ANALYSIS.md} — affected classes
 * (direct mentions plus one hop of dependencies), HTTP endpoints, database
 * tables, call paths from entry points, and the tests that cover them.
 *
 * <p>Outputs: {@code classes}, {@code endpoints}, {@code tables}.</p>
 */
public final class ImpactAnalysisAgent implements Agent {

    private final Path repoRoot;
    private final String productPackagePath;

    /**
     * Creates the agent.
     *
     * @param repoRoot           repository to analyse
     * @param productPackagePath package directory of the product under change, relative to the
     *                           source roots (e.g. {@code com/agentsdlc/shortener}); tooling in the
     *                           same repository is out of scope for the blast radius
     */
    public ImpactAnalysisAgent(Path repoRoot, String productPackagePath) {
        this.repoRoot = repoRoot;
        this.productPackagePath = productPackagePath;
    }

    @Override
    public void execute(TaskContext ctx) {
        List<String> keywords = AgentSupport.jsonList(ctx.require("triage", "keywords"));
        String mainRoot = "src/main/java/" + productPackagePath;
        SourceIndex main = SourceIndex.scan(repoRoot, mainRoot);
        if (main.size() == 0) {
            throw new IllegalStateException("no sources found under " + repoRoot.resolve(mainRoot));
        }
        Set<String> direct = main.mentioning(keywords);
        Set<String> affected = new TreeSet<>(direct);
        affected.addAll(main.referencedBy(direct));

        List<String> endpoints = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        Set<String> entryPoints = new TreeSet<>();
        for (String c : affected) {
            List<String> own = main.endpoints(c);
            if (!own.isEmpty()) {
                entryPoints.add(c);
                own.forEach(e -> endpoints.add(e + " (" + c + ")"));
            }
            main.tables(c).forEach(t -> tables.add(t + " (" + c + ")"));
        }
        Set<String> tests = SourceIndex.scan(repoRoot, "src/test/java/" + productPackagePath).mentioning(keywords);

        StringBuilder md = new StringBuilder("# Impact analysis\n\nKeywords from triage: " + keywords
                + "\n\nMethod: regex index of `" + mainRoot + "`; classes mentioning a keyword, plus one hop of "
                + "their dependencies. Over-reporting is intended (safe direction).\n\n## Affected classes\n\n");
        for (String c : affected) {
            md.append("- `").append(c).append("` — ").append(main.pathOf(c))
                    .append(direct.contains(c) ? " (direct)" : " (dependency)").append("\n");
        }
        md.append("\n## Endpoints\n\n");
        endpoints.forEach(e -> md.append("- ").append(e).append("\n"));
        md.append("\n## Tables\n\n");
        tables.forEach(t -> md.append("- ").append(t).append("\n"));
        md.append("\n## Call paths\n\n");
        main.callPaths(entryPoints, affected).forEach(p -> md.append("- ").append(p).append("\n"));
        md.append("\n## Tests covering the area\n\n");
        tests.forEach(t -> md.append("- `").append(t).append("`\n"));

        ctx.writeArtifact(ImpactAnalysisGate.REPORT, md.toString());
        ctx.put("classes", AgentSupport.toJson(List.copyOf(affected)));
        ctx.put("endpoints", AgentSupport.toJson(endpoints));
        ctx.put("tables", AgentSupport.toJson(tables));
        ctx.decide("blast radius: " + affected.size() + " classes, " + endpoints.size() + " endpoints, "
                        + tables.size() + " tables",
                "a fix must not change behaviour of the listed endpoints beyond the race", Map.of(
                        "keywords", keywords.toString()));
    }
}
