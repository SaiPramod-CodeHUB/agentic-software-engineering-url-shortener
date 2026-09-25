package com.agentsdlc.shortener;

import java.time.Instant;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the wall clock and the secure random source with deterministic test doubles. */
@TestConfiguration
public class TestClockConfig {

    /**
     * A controllable clock starting at a fixed instant.
     *
     * @return the test clock
     */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    /**
     * A fixed-seed random source so generated codes are reproducible.
     *
     * @return seeded random
     */
    @Bean
    @Primary
    public RandomGenerator testCodeRandom() {
        return new Random(42);
    }
}
