package com.agentsdlc.orchestrator.approval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A real human answering clarifying questions at the terminal. Blank
 * answers are left out, so a spec that still lacks information stays a draft.
 */
public final class ConsoleClarificationProvider implements ClarificationProvider {

    private final ConsolePrompter prompter;

    /**
     * Creates the provider.
     *
     * @param prompter terminal channel
     */
    public ConsoleClarificationProvider(ConsolePrompter prompter) {
        this.prompter = prompter;
    }

    @Override
    public Map<String, String> answer(Map<String, String> questions) {
        Map<String, String> answers = new LinkedHashMap<>();
        prompter.say("\nThe request is ambiguous. Please answer (blank to skip):");
        new TreeMap<>(questions).forEach((id, question) -> prompter.ask("  " + id + ": " + question + "\n  > ")
                .filter(a -> !a.isBlank())
                .ifPresent(a -> answers.put(id, a)));
        return answers;
    }
}
