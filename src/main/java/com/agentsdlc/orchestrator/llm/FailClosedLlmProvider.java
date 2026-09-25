package com.agentsdlc.orchestrator.llm;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a remote provider and falls back to a local one on any failure or
 * empty answer. "Fail closed" here means the pipeline degrades to the
 * known-good deterministic behaviour instead of proceeding on a missing or
 * partial model response.
 */
public final class FailClosedLlmProvider implements LlmProvider {

    private final LlmProvider primary;
    private final LlmProvider fallback;
    private final AtomicInteger fallbacks = new AtomicInteger();

    /**
     * Creates the wrapper.
     *
     * @param primary  provider tried first (e.g. a hosted model)
     * @param fallback provider used when the primary fails
     */
    public FailClosedLlmProvider(LlmProvider primary, LlmProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return primary.name() + "|fallback:" + fallback.name();
    }

    @Override
    public String complete(LlmRequest request) {
        String reason;
        try {
            String answer = primary.complete(request);
            if (answer != null && !answer.isBlank()) {
                return answer;
            }
            reason = "empty answer";
        } catch (RuntimeException e) {
            reason = e.getMessage();
        }
        // Visible, but without request content or credentials: only the provider and the cause.
        if (fallbacks.incrementAndGet() <= 3) {
            System.err.println("[llm] " + primary.name() + " failed (" + reason + ") for purpose '"
                    + request.purpose() + "'; using " + fallback.name());
        }
        return fallback.complete(request);
    }

    /**
     * Number of calls answered by the fallback.
     *
     * @return fallback count
     */
    public int fallbackCount() {
        return fallbacks.get();
    }
}
