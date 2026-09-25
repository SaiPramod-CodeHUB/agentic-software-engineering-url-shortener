package com.agentsdlc.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the shortener, bound from the {@code shortener.*}
 * properties.
 *
 * @param baseUrl         public base URL used to build {@code shortUrl} values
 * @param codeLength      length of generated base62 codes
 * @param maxTtlSeconds   upper bound accepted for {@code ttlSeconds}
 * @param rateLimit       per-IP rate-limit settings for {@code POST /shorten}
 * @param visitorHashKey  HMAC key used to pseudonymise client IPs for analytics
 */
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        String baseUrl,
        int codeLength,
        long maxTtlSeconds,
        RateLimit rateLimit,
        String visitorHashKey) {

    /**
     * Token-bucket settings.
     *
     * @param capacity      maximum burst of requests per client
     * @param windowSeconds time for an empty bucket to refill completely
     */
    public record RateLimit(int capacity, long windowSeconds) {
    }
}
