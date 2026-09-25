package com.agentsdlc.shortener.api;

/**
 * Uniform error body for every non-2xx/3xx response.
 *
 * @param error   stable machine-readable code, e.g. {@code alias_taken}
 * @param message human-readable explanation
 */
public record ErrorResponse(String error, String message) {
}
