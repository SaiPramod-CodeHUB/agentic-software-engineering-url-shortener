package com.agentsdlc.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentsdlc.shortener.service.CodeGenerator;
import com.agentsdlc.shortener.service.RateLimitedException;
import com.agentsdlc.shortener.service.RateLimiter;
import com.agentsdlc.shortener.service.ShortenerException;
import com.agentsdlc.shortener.service.UrlSafetyValidator;
import com.agentsdlc.shortener.service.VisitorHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for the service's building blocks; no Spring context. */
class ServiceUnitTest {

    private final UrlSafetyValidator validator = new UrlSafetyValidator();

    @Test
    void codesUseBase62AndAreReproducibleWithAFixedSeed() {
        CodeGenerator a = new CodeGenerator(new Random(7), 8);
        CodeGenerator b = new CodeGenerator(new Random(7), 8);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = a.next();
            assertThat(code).hasSize(8).matches("[0-9A-Za-z]{8}");
            assertThat(code).isEqualTo(b.next());
            seen.add(code);
        }
        assertThat(seen).hasSize(1000);
        assertThatThrownBy(() -> new CodeGenerator(new Random(1), 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/path?q=1",
        "http://example.org",
        "https://8.8.8.8/dns",
        "https://[2606:4700:4700::1111]/",
        "HTTPS://Example.COM/Upper"
    })
    void acceptsPublicHttpUrls(String url) {
        assertThat(validator.validate(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://example.com/file",
        "javascript:alert(1)",
        "file:///etc/passwd",
        "http://localhost:8080/admin",
        "http://printer.local/",
        "http://metadata.google.internal/computeMetadata/v1/",
        "http://127.0.0.1/",
        "http://10.1.2.3/",
        "http://172.16.0.1/",
        "http://192.168.1.1/",
        "http://169.254.169.254/latest/meta-data/",
        "http://100.64.0.1/",
        "http://0.0.0.0/",
        "http://[::1]/",
        "http://[fd00::1]/",
        "http://[::ffff:127.0.0.1]/",
        "http://2130706433/",
        "http://0x7f000001/",
        "http://127.1/",
        "http://user:pass@example.com/",
        "not a url",
        ""
    })
    void rejectsUnsafeOrMalformedUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOfSatisfying(ShortenerException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
    }

    @Test
    void rateLimiterAllowsBurstThenRejectsWithRetryAfterThenRefills() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiter limiter = new RateLimiter(clock, 3, 60);
        limiter.acquire("client-a");
        limiter.acquire("client-a");
        limiter.acquire("client-a");
        assertThatThrownBy(() -> limiter.acquire("client-a"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(20));
        limiter.acquire("client-b"); // buckets are per client
        clock.advance(Duration.ofSeconds(20));
        limiter.acquire("client-a"); // one token refilled
        assertThatThrownBy(() -> limiter.acquire("client-a")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void visitorHashIsStableKeyedAndNeverContainsTheAddress() {
        VisitorHasher hasher = new VisitorHasher("k1");
        String h = hasher.hash("203.0.113.9");
        assertThat(h).hasSize(64).isEqualTo(hasher.hash("203.0.113.9")).doesNotContain("203.0.113.9");
        assertThat(new VisitorHasher("k2").hash("203.0.113.9")).isNotEqualTo(h);
        assertThatThrownBy(() -> new VisitorHasher(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
