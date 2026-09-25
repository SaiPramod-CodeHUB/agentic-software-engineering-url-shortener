package com.agentsdlc.shortener;

import com.agentsdlc.shortener.config.ShortenerProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Entry point of the URL shortener service (Part A of the repository).
 *
 * <p>Besides bootstrapping Spring, it publishes the two sources of
 * non-determinism in the service as beans — the wall clock and the random
 * generator — so that tests can replace both and stay reproducible.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(ShortenerProperties.class)
public class ShortenerApplication {

    /** Creates the application configuration; instantiated by Spring. */
    public ShortenerApplication() {
        // Spring instantiates this configuration class reflectively.
    }

    /**
     * Starts the HTTP service.
     *
     * @param args standard Spring Boot command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(ShortenerApplication.class, args);
    }

    /**
     * The clock used for TTL expiry, click timestamps and rate limiting.
     *
     * @return the UTC system clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The random source for short codes. Codes are bearer tokens, so the
     * production source must be cryptographically secure.
     *
     * @return a {@link SecureRandom}
     */
    @Bean
    public RandomGenerator codeRandom() {
        return new SecureRandom();
    }
}
