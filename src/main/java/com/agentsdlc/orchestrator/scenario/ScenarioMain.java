package com.agentsdlc.orchestrator.scenario;

import com.agentsdlc.orchestrator.approval.ConsoleApprovalProvider;
import com.agentsdlc.orchestrator.approval.ConsoleClarificationProvider;
import com.agentsdlc.orchestrator.approval.ConsolePrompter;
import com.agentsdlc.orchestrator.llm.LlmProviders;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point.
 * <ul>
 *   <li>{@code ScenarioMain <greenfield|brownfield|ambiguous> [outDir] [repoRoot]}: a fixed scenario.</li>
 *   <li>{@code ScenarioMain request [--auto-approve] "<requirement>"}: any requirement, with live
 *       human approvals and clarifications at the terminal.</li>
 * </ul>
 * Exits 0 only when every check passed.
 */
public final class ScenarioMain {

    private ScenarioMain() {
    }

    /**
     * Runs one scenario.
     *
     * @param args see the class description
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            usage();
        }
        ScenarioResult result = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "greenfield" -> GreenfieldScenario.run(out(args), repo(args), System.out);
            case "brownfield" -> BrownfieldScenario.run(out(args), repo(args), System.out);
            case "ambiguous" -> AmbiguousScenario.run(out(args), repo(args), System.out);
            case "request" -> runRequest(args);
            default -> {
                usage();
                yield null;
            }
        };
        System.exit(result.success() ? 0 : 1);
    }

    private static ScenarioResult runRequest(String[] args) {
        boolean autoApprove = false;
        List<String> words = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--auto-approve")) {
                autoApprove = true;
            } else {
                words.add(args[i]);
            }
        }
        String request = String.join(" ", words).trim();
        if (request.isEmpty()) {
            usage();
        }
        ConsolePrompter prompter = new ConsolePrompter(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        String approver = System.getProperty("user.name", "human");
        return RequestScenario.run(request, Path.of("working_tree"), Path.of("."), LlmProviders.fromEnvironment(),
                new ConsoleApprovalProvider(prompter, approver, autoApprove, Clock.systemUTC()),
                new ConsoleClarificationProvider(prompter), System.out);
    }

    private static Path out(String[] args) {
        return Path.of(args.length > 1 ? args[1] : "working_tree");
    }

    private static Path repo(String[] args) {
        return Path.of(args.length > 2 ? args[2] : ".");
    }

    private static void usage() {
        System.err.println("usage: ScenarioMain <greenfield|brownfield|ambiguous> [outDir] [repoRoot]");
        System.err.println("       ScenarioMain request [--auto-approve] \"<requirement>\"");
        System.exit(2);
    }
}
