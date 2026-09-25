package com.agentsdlc.shortener.service;

import com.agentsdlc.shortener.config.ShortenerProperties;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory token-bucket rate limiter keyed by client identity.
 *
 * <p>A token bucket allows short bursts (up to {@code capacity}) while
 * enforcing a sustained rate, and unlike a fixed window it has no boundary
 * spike where a client gets 2× the limit across a window edge. State is
 * per-instance: behind a load balancer with N replicas the effective limit is
 * N× — acceptable for this service, and the class is the seam for a Redis or
 * gateway-based limiter.</p>
 */
@Component
public class RateLimiter {

    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Clock clock;
    private final int capacity;
    private final double tokensPerNano;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Creates a limiter from configuration.
     *
     * @param clock      time source (injected so tests control time)
     * @param properties supplies capacity and refill window
     */
    @Autowired
    public RateLimiter(Clock clock, ShortenerProperties properties) {
        this(clock, properties.rateLimit().capacity(), properties.rateLimit().windowSeconds());
    }

    /**
     * Creates a limiter with explicit settings.
     *
     * @param clock         time source
     * @param capacity      maximum burst size; must be positive
     * @param windowSeconds seconds to refill an empty bucket; must be positive
     */
    public RateLimiter(Clock clock, int capacity, long windowSeconds) {
        if (capacity <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("capacity and windowSeconds must be positive");
        }
        this.clock = clock;
        this.capacity = capacity;
        this.tokensPerNano = capacity / (windowSeconds * 1_000_000_000.0);
    }

    /**
     * Takes one token for the client or rejects the request.
     *
     * @param clientKey stable client identity (a pseudonymised IP)
     * @throws RateLimitedException when the bucket is empty
     */
    public void acquire(String clientKey) {
        long now = nowNanos();
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            evictFullBuckets(now);
        }
        long[] waitNanos = {0};
        // compute() runs atomically per key, so concurrent requests from one client cannot double-spend.
        buckets.compute(clientKey, (k, bucket) -> {
            Bucket b = bucket == null ? new Bucket(capacity, now) : bucket.refill(now, capacity, tokensPerNano);
            if (b.tokens >= 1.0) {
                return new Bucket(b.tokens - 1.0, now);
            }
            waitNanos[0] = (long) Math.ceil((1.0 - b.tokens) / tokensPerNano);
            return b;
        });
        if (waitNanos[0] > 0) {
            throw new RateLimitedException(Math.max(1, (long) Math.ceil(waitNanos[0] / 1_000_000_000.0)));
        }
    }

    private void evictFullBuckets(long now) {
        buckets.entrySet().removeIf(e -> e.getValue().refill(now, capacity, tokensPerNano).tokens >= capacity);
    }

    private long nowNanos() {
        var instant = clock.instant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private record Bucket(double tokens, long updatedNanos) {
        Bucket refill(long now, int capacity, double tokensPerNano) {
            double refilled = Math.min(capacity, tokens + Math.max(0, now - updatedNanos) * tokensPerNano);
            return new Bucket(refilled, now);
        }
    }
}
