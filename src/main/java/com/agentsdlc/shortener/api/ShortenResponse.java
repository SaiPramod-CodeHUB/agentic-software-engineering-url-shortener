package com.agentsdlc.shortener.api;

/**
 * Response of {@code POST /shorten}.
 *
 * @param code     the short code
 * @param shortUrl absolute short URL
 * @param created  {@code true} for a new link (201), {@code false} for an idempotent replay (200)
 */
public record ShortenResponse(String code, String shortUrl, boolean created) {
}
