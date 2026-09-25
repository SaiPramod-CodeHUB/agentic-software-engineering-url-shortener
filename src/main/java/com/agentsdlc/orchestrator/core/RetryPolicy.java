package com.agentsdlc.orchestrator.core;

import java.time.Duration;

/**
 * Bounded retries with exponential backoff.
 *
 * @param maxAttempts    total attempts including the first; at least 1
 * @param initialBackoff delay before the second attempt
 * @param multiplier     factor applied to the delay for each further attempt; at least 1
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier) {

    /** Cap so a misconfigured multiplier cannot stall a pipeline for hours. */
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /**
     * Validates the policy.
     *
     * @param maxAttempts    total attempts including the first
     * @param initialBackoff delay before the second attempt
     * @param multiplier     backoff growth factor
     */
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must be >= 0");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
    }

    /**
     * A policy with a single attempt.
     *
     * @return no-retry policy
     */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 1.0);
    }

    /**
     * A policy doubling the backoff after each failure.
     *
     * @param maxAttempts    total attempts
     * @param initialBackoff first delay
     * @return the policy
     */
    public static RetryPolicy exponential(int maxAttempts, Duration initialBackoff) {
        return new RetryPolicy(maxAttempts, initialBackoff, 2.0);
    }

    /**
     * Delay to wait before the given attempt.
     *
     * @param attempt the upcoming attempt number, starting at 2 for the first retry
     * @return {@code initialBackoff * multiplier^(attempt-2)}, capped at {@link #MAX_BACKOFF}
     */
    public Duration backoffBefore(int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 2);
        return millis >= MAX_BACKOFF.toMillis() ? MAX_BACKOFF : Duration.ofMillis((long) millis);
    }
}
