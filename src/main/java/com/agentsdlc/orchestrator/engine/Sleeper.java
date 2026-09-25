package com.agentsdlc.orchestrator.engine;

import java.time.Duration;

/**
 * Abstraction over waiting between retries, so tests can assert the backoff
 * schedule without actually sleeping (and without wall-clock assertions).
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * Waits.
     *
     * @param duration how long
     * @throws InterruptedException when interrupted
     */
    void sleep(Duration duration) throws InterruptedException;

    /**
     * The real implementation.
     *
     * @return a sleeper backed by {@link Thread#sleep(Duration)}
     */
    static Sleeper real() {
        return Thread::sleep;
    }
}
