package com.agentsdlc.shortener.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /shorten}.
 *
 * @param url         destination URL (required)
 * @param customAlias optional client-chosen code
 * @param ttlSeconds  optional lifetime in seconds
 */
public record ShortenRequest(@NotBlank String url, String customAlias, Long ttlSeconds) {
}
