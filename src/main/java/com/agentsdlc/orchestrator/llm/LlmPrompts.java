package com.agentsdlc.orchestrator.llm;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Output contracts for hosted models, one per request purpose. The offline
 * provider ignores these (it routes on {@code purpose} and templates); a real
 * model needs to be told exactly what shape to return, because every answer
 * is parsed or compiled by code, not read by a person.
 */
public final class LlmPrompts {

    private LlmPrompts() {
    }

    /**
     * Builds the system instruction for a request: the agent's own system
     * text plus the strict output contract for its purpose.
     *
     * @param request the request
     * @return system instruction
     */
    public static String system(LlmRequest request) {
        return request.system() + "\n\n" + contract(request);
    }

    /**
     * Builds the user message: the prompt followed by the structured inputs.
     *
     * @param request the request
     * @return user message
     */
    public static String user(LlmRequest request) {
        StringBuilder sb = new StringBuilder(request.prompt());
        Map<String, String> vars = new TreeMap<>(request.vars());
        vars.remove("template");
        if (!vars.isEmpty()) {
            sb.append("\n\nInputs:\n");
            vars.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        }
        return sb.toString();
    }

    /**
     * Java package generated code must use for a feature, e.g. {@code custom-domains} to
     * {@code generated.customdomains}.
     *
     * @param feature feature key
     * @return package name
     */
    public static String packageFor(String feature) {
        String cleaned = feature == null ? "" : feature.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return "generated." + (cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0)) ? "feature" + cleaned
                : cleaned);
    }

    private static String contract(LlmRequest request) {
        String template = request.vars().getOrDefault("template", "");
        return switch (request.purpose()) {
            case "normalize" -> """
                    Return ONLY one JSON object: no prose, no code fences. Keys:
                    title (string), kind ("FEATURE" or "INCIDENT"),
                    feature (short kebab-case id of what to build, or "unknown" if you cannot tell),
                    goals (array of strings), acceptanceCriteria (array of testable strings),
                    constraints (array of strings),
                    ambiguityScore (number from 0 to 1; 0.5 or more means too vague to build safely),
                    openQuestions (object mapping "q1","q2",... to clarifying questions; {} when none).
                    If clarifications are given in the inputs, use them and lower the score accordingly.
                    """;
            case "triage" -> """
                    Return ONLY one JSON object: no prose, no code fences. Keys:
                    severity ("SEV1".."SEV4"), component (string), hypothesis (string),
                    keywords (array of identifiers likely to appear in the affected source code).
                    """;
            case "design" -> "Return concise Markdown: components, data, API sketch, decisions and trade-offs.";
            case "docs" -> "Return concise Markdown user documentation for the implemented feature.";
            case "code" -> codeContract(template, request.vars().getOrDefault("feature", "feature"));
            default -> "Answer concisely.";
        };
    }

    private static String codeContract(String template, String feature) {
        String pkg = packageFor(feature);
        if (template.endsWith("/test")) {
            return "Return ONLY one complete JUnit 5 test source file (org.junit.jupiter.api), no prose. "
                    + "Same package as the implementation. At least 3 @Test methods covering the acceptance "
                    + "criteria and edge cases. Use only the JDK and JUnit 5 assertions. The test class must "
                    + "not be public.";
        }
        return "Return ONLY one complete, compilable Java 21 source file, no prose. Use package " + pkg
                + ". Exactly one public top-level class. Only the JDK standard library: no frameworks, no I/O, "
                + "no network, no System.exit. Validate inputs and throw IllegalArgumentException on bad input.";
    }
}
