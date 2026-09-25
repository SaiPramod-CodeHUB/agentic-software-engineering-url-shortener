package com.agentsdlc.orchestrator.llm;

import java.util.Map;

/**
 * A request to a language model.
 *
 * @param purpose what the call is for ({@code normalize}, {@code triage},
 *                {@code implement}, {@code test}, ...); lets the offline
 *                provider route deterministically and lets real providers pick prompts
 * @param system  system instruction
 * @param prompt  user prompt
 * @param vars    structured inputs (e.g. {@code feature}, {@code clarifications})
 */
public record LlmRequest(String purpose, String system, String prompt, Map<String, String> vars) {

    /**
     * Copies {@code vars} defensively.
     *
     * @param purpose purpose tag
     * @param system  system instruction
     * @param prompt  user prompt
     * @param vars    structured inputs
     */
    public LlmRequest {
        vars = vars == null ? Map.of() : Map.copyOf(vars);
    }
}
