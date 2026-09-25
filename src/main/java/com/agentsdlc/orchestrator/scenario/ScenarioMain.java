package com.agentsdlc.orchestrator.scenario;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Command-line entry point: {@code ScenarioMain <greenfield|brownfield|ambiguous> [outDir] [repoRoot]}.
 * Exits 0 only when every check of the scenario passed.
 */
public final class ScenarioMain {

    private ScenarioMain() {
    }

    /**
     * Runs one scenario.
     *
     * @param args scenario name, optional output directory (default {@code working_tree}),
     *             optional repository root (default current directory)
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: ScenarioMain <greenfield|brownfield|ambiguous> [outDir] [repoRoot]");
            System.exit(2);
        }
        Path out = Path.of(args.length > 1 ? args[1] : "working_tree");
        Path repo = Path.of(args.length > 2 ? args[2] : ".");
        ScenarioResult result = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "greenfield" -> GreenfieldScenario.run(out, repo, System.out);
            case "brownfield" -> BrownfieldScenario.run(out, repo, System.out);
            case "ambiguous" -> AmbiguousScenario.run(out, repo, System.out);
            default -> {
                System.err.println("unknown scenario: " + args[0]);
                System.exit(2);
                yield null;
            }
        };
        System.exit(result.success() ? 0 : 1);
    }
}
