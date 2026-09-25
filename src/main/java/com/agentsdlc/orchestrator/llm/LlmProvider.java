package com.agentsdlc.orchestrator.llm;

/**
 * The seam between agents and any language model. Agents depend only on this
 * interface; the default implementation is the offline
 * {@link DeterministicLlmProvider}, and {@link LlmProviders#fromEnvironment()}
 * can swap in a hosted model without touching agent code.
 */
public interface LlmProvider {

    /**
     * Short provider name for audit records.
     *
     * @return provider name
     */
    String name();

    /**
     * Produces a completion.
     *
     * @param request the request
     * @return the model's text output
     * @throws LlmException when the provider cannot answer
     */
    String complete(LlmRequest request);

    /** Raised when a provider fails; callers fail closed to the deterministic provider. */
    class LlmException extends RuntimeException {

        /**
         * Creates the exception.
         *
         * @param message what failed
         * @param cause   underlying cause, may be {@code null}
         */
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
