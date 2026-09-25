package com.agentsdlc.orchestrator.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleans model output before it is parsed or compiled. */
public final class LlmOutput {

    private static final Pattern FENCED = Pattern.compile("(?s)```[a-zA-Z0-9_-]*\\s*\\n(.*?)\\n?```");

    private LlmOutput() {
    }

    /**
     * Returns the body of the first Markdown code fence, or the trimmed text
     * when there is none. Models often wrap JSON or Java in fences even when
     * told not to; stripping them is cheaper than failing the attempt.
     *
     * @param text raw model output
     * @return the payload
     */
    public static String stripFences(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = FENCED.matcher(text);
        return m.find() ? m.group(1).trim() : text.trim();
    }
}
