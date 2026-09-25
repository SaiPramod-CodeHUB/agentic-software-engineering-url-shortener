package com.agentsdlc.shortener.service;

import org.springframework.http.HttpStatus;

/** 429: the client exhausted its token bucket. Carries the {@code Retry-After} value. */
public class RateLimitedException extends ShortenerException {

    /** Seconds until the client may retry. */
    private final long retryAfterSeconds;

    /**
     * Creates the exception.
     *
     * @param retryAfterSeconds whole seconds until the next token is available
     */
    public RateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "too many requests");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns the number of seconds the client should wait.
     *
     * @return seconds until a retry can succeed (at least 1)
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
