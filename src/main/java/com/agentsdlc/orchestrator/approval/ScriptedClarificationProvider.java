package com.agentsdlc.orchestrator.approval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stand-in for a human answering clarifying questions. Counts
 * calls so a scenario can prove the human was asked exactly once.
 */
public final class ScriptedClarificationProvider implements ClarificationProvider {

    private final Map<String, String> answers;
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * Creates the provider.
     *
     * @param answers scripted answers by question id
     */
    public ScriptedClarificationProvider(Map<String, String> answers) {
        this.answers = Map.copyOf(answers);
    }

    @Override
    public Map<String, String> answer(Map<String, String> questions) {
        calls.incrementAndGet();
        Map<String, String> result = new LinkedHashMap<>();
        questions.keySet().stream().sorted().forEach(id -> {
            if (answers.containsKey(id)) {
                result.put(id, answers.get(id));
            }
        });
        return result;
    }

    /**
     * Number of times a human was asked.
     *
     * @return call count
     */
    public int calls() {
        return calls.get();
    }
}
