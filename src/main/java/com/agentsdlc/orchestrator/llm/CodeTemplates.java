package com.agentsdlc.orchestrator.llm;

import java.util.Map;

/**
 * Source code the offline provider "generates". Each entry is real,
 * compilable Java that the tester agent compiles and runs; the templates
 * stand in for model output so the whole pipeline is reproducible without
 * network access. With a hosted model plugged in, the same agents receive
 * model-written code instead and the same gates judge it.
 */
public final class CodeTemplates {

    private CodeTemplates() {
    }

    /** Greenfield: smallest QR version for a payload. */
    static final String QR_MAIN = """
            package generated.qr;

            import java.nio.charset.StandardCharsets;

            /** Chooses the smallest QR code version able to encode a short URL. */
            public final class QrCodeSizer {

                /** Byte-mode capacity at error-correction level M, versions 1..10 (ISO/IEC 18004). */
                private static final int[] BYTE_CAPACITY_M = {14, 26, 42, 62, 84, 106, 122, 152, 180, 213};

                private QrCodeSizer() {
                }

                /** Returns the smallest version (1..10) whose level-M byte capacity fits the payload. */
                public static int minimalVersion(String payload) {
                    if (payload == null || payload.isEmpty()) {
                        throw new IllegalArgumentException("payload must not be empty");
                    }
                    int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
                    for (int version = 1; version <= BYTE_CAPACITY_M.length; version++) {
                        if (bytes <= BYTE_CAPACITY_M[version - 1]) {
                            return version;
                        }
                    }
                    throw new IllegalArgumentException(
                            "payload of " + bytes + " bytes exceeds the version 10-M capacity of 213 bytes");
                }

                /** Returns the width in modules of a QR symbol: 17 + 4 * version. */
                public static int moduleCount(int version) {
                    if (version < 1 || version > 40) {
                        throw new IllegalArgumentException("version must be between 1 and 40");
                    }
                    return 17 + 4 * version;
                }
            }
            """;

    /** Greenfield: unit tests for {@link #QR_MAIN}. */
    static final String QR_TEST = """
            package generated.qr;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertThrows;

            import org.junit.jupiter.api.Test;

            class QrCodeSizerTest {

                @Test
                void typicalShortUrlFitsVersionTwo() {
                    assertEquals(2, QrCodeSizer.minimalVersion("https://sho.rt/Ab3dE5gH"));
                }

                @Test
                void capacityBoundariesAreInclusive() {
                    assertEquals(1, QrCodeSizer.minimalVersion("https://a.b/cd"));
                    assertEquals(2, QrCodeSizer.minimalVersion("https://a.b/cde"));
                    assertEquals(10, QrCodeSizer.minimalVersion("a".repeat(213)));
                }

                @Test
                void oversizedAndEmptyPayloadsAreRejected() {
                    assertThrows(IllegalArgumentException.class, () -> QrCodeSizer.minimalVersion("a".repeat(214)));
                    assertThrows(IllegalArgumentException.class, () -> QrCodeSizer.minimalVersion(""));
                }

                @Test
                void moduleCountFollowsTheStandard() {
                    assertEquals(21, QrCodeSizer.moduleCount(1));
                    assertEquals(57, QrCodeSizer.moduleCount(10));
                    assertThrows(IllegalArgumentException.class, () -> QrCodeSizer.moduleCount(0));
                }
            }
            """;

    /** Ambiguous-then-clarified: idle-link expiry policy. */
    static final String IDLE_MAIN = """
            package generated.expiry;

            import java.time.Duration;
            import java.time.Instant;

            /** Expires links that have not been clicked for longer than an idle limit. */
            public final class IdleExpiryPolicy {

                private final Duration idleLimit;

                /** Creates the policy; the limit must be positive. */
                public IdleExpiryPolicy(Duration idleLimit) {
                    if (idleLimit == null || idleLimit.isZero() || idleLimit.isNegative()) {
                        throw new IllegalArgumentException("idle limit must be positive");
                    }
                    this.idleLimit = idleLimit;
                }

                /**
                 * Whether the link is expired. Links never clicked count from creation;
                 * any click resets the idle timer.
                 */
                public boolean isExpired(Instant createdAt, Instant lastClickAt, Instant now) {
                    Instant lastActivity = lastClickAt == null ? createdAt : lastClickAt;
                    return !now.isBefore(lastActivity.plus(idleLimit));
                }

                /** HTTP status the redirect endpoint should return. */
                public int redirectStatus(Instant createdAt, Instant lastClickAt, Instant now) {
                    return isExpired(createdAt, lastClickAt, now) ? 410 : 302;
                }
            }
            """;

    /** Tests for {@link #IDLE_MAIN}. */
    static final String IDLE_TEST = """
            package generated.expiry;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertThrows;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            import java.time.Duration;
            import java.time.Instant;
            import org.junit.jupiter.api.Test;

            class IdleExpiryPolicyTest {

                private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
                private final IdleExpiryPolicy policy = new IdleExpiryPolicy(Duration.ofDays(30));

                @Test
                void neverClickedLinkExpiresThirtyDaysAfterCreation() {
                    assertFalse(policy.isExpired(CREATED, null, CREATED.plus(Duration.ofDays(29))));
                    assertTrue(policy.isExpired(CREATED, null, CREATED.plus(Duration.ofDays(30))));
                    assertEquals(410, policy.redirectStatus(CREATED, null, CREATED.plus(Duration.ofDays(31))));
                }

                @Test
                void aClickResetsTheIdleTimer() {
                    Instant click = CREATED.plus(Duration.ofDays(20));
                    assertFalse(policy.isExpired(CREATED, click, CREATED.plus(Duration.ofDays(45))));
                    assertEquals(302, policy.redirectStatus(CREATED, click, CREATED.plus(Duration.ofDays(45))));
                    assertTrue(policy.isExpired(CREATED, click, CREATED.plus(Duration.ofDays(50))));
                }

                @Test
                void nonPositiveLimitIsRejected() {
                    assertThrows(IllegalArgumentException.class, () -> new IdleExpiryPolicy(Duration.ZERO));
                }
            }
            """;

    /** Brownfield fixture: the legacy check-then-act alias registry (the bug). */
    static final String ALIAS_LEGACY = """
            package legacy.alias;

            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;

            /** Legacy alias registry. Registers custom aliases for short links. */
            public class AliasRegistry {

                /** Test hook run between the existence check and the write; a no-op in production. */
                public static volatile Runnable faultInjection = () -> { };

                private final Map<String, String> aliases = new ConcurrentHashMap<>();

                /** Registers an alias; returns false when it is already taken. */
                public boolean register(String alias, String url) {
                    if (aliases.containsKey(alias)) {
                        return false;
                    }
                    faultInjection.run();
                    aliases.put(alias, url);
                    return true;
                }

                /** Returns the target for an alias, or null. */
                public String resolve(String alias) {
                    return aliases.get(alias);
                }
            }
            """;

    /** Brownfield: regression test that reproduces the race deterministically. */
    static final String ALIAS_REGRESSION_TEST = """
            package legacy.alias;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import java.util.concurrent.CyclicBarrier;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Executors;
            import java.util.concurrent.Future;
            import java.util.concurrent.TimeUnit;
            import org.junit.jupiter.api.AfterEach;
            import org.junit.jupiter.api.Test;

            class AliasRegistryRaceTest {

                @AfterEach
                void resetHook() {
                    AliasRegistry.faultInjection = () -> { };
                }

                @Test
                void concurrentRegistrationOfSameAliasHasExactlyOneWinner() throws Exception {
                    AliasRegistry registry = new AliasRegistry();
                    CyclicBarrier bothInside = new CyclicBarrier(2);
                    // Fault injection: hold each caller inside the critical window until both are there,
                    // which forces the interleaving that production hits only under load.
                    AliasRegistry.faultInjection = () -> {
                        try {
                            bothInside.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                        }
                    };
                    ExecutorService pool = Executors.newFixedThreadPool(2);
                    try {
                        Future<Boolean> a = pool.submit(() -> registry.register("promo", "https://example.com/a"));
                        Future<Boolean> b = pool.submit(() -> registry.register("promo", "https://example.com/b"));
                        boolean aWon = a.get(10, TimeUnit.SECONDS);
                        boolean bWon = b.get(10, TimeUnit.SECONDS);
                        assertEquals(1, (aWon ? 1 : 0) + (bWon ? 1 : 0), "exactly one caller may win the alias");
                        String expected = aWon ? "https://example.com/a" : "https://example.com/b";
                        assertEquals(expected, registry.resolve("promo"), "the winner's target must not be overwritten");
                    } finally {
                        pool.shutdownNow();
                    }
                }
            }
            """;

    /** Brownfield: the fix — an atomic claim instead of check-then-act. */
    static final String ALIAS_FIXED = """
            package legacy.alias;

            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;

            /** Alias registry. The claim is atomic, so concurrent callers get exactly one winner. */
            public class AliasRegistry {

                /** Test hook run between the existence check and the write; a no-op in production. */
                public static volatile Runnable faultInjection = () -> { };

                private final Map<String, String> aliases = new ConcurrentHashMap<>();

                /** Registers an alias; returns false when it is already taken. */
                public boolean register(String alias, String url) {
                    if (aliases.containsKey(alias)) {
                        return false; // fast path only; correctness comes from putIfAbsent below
                    }
                    faultInjection.run();
                    return aliases.putIfAbsent(alias, url) == null;
                }

                /** Returns the target for an alias, or null. */
                public String resolve(String alias) {
                    return aliases.get(alias);
                }
            }
            """;

    /** Brownfield: behaviour-preserving refactor of {@link #ALIAS_FIXED}. */
    static final String ALIAS_REFACTORED = """
            package legacy.alias;

            import java.util.Map;
            import java.util.Objects;
            import java.util.concurrent.ConcurrentHashMap;

            /**
             * Alias registry: maps a custom alias to its target URL.
             * First registration wins; later registrations of the same alias are refused.
             */
            public class AliasRegistry {

                /** Test hook run inside the claim window; a no-op in production. */
                public static volatile Runnable faultInjection = () -> { };

                private final Map<String, String> targetsByAlias = new ConcurrentHashMap<>();

                /** Registers an alias; returns false when it is already taken. */
                public boolean register(String alias, String url) {
                    Objects.requireNonNull(alias, "alias");
                    Objects.requireNonNull(url, "url");
                    return !isTaken(alias) && claim(alias, url);
                }

                /** Returns the target for an alias, or null. */
                public String resolve(String alias) {
                    return targetsByAlias.get(alias);
                }

                private boolean isTaken(String alias) {
                    return targetsByAlias.containsKey(alias);
                }

                private boolean claim(String alias, String url) {
                    faultInjection.run();
                    return targetsByAlias.putIfAbsent(alias, url) == null;
                }
            }
            """;

    /** Brownfield: extra edge-case tests raising coverage. */
    static final String ALIAS_EDGE_TEST = """
            package legacy.alias;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertNull;
            import static org.junit.jupiter.api.Assertions.assertThrows;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            import org.junit.jupiter.api.Test;

            class AliasRegistryEdgeCaseTest {

                private final AliasRegistry registry = new AliasRegistry();

                @Test
                void unknownAliasResolvesToNull() {
                    assertNull(registry.resolve("missing"));
                }

                @Test
                void sequentialDuplicateIsRefusedAndFirstTargetKept() {
                    assertTrue(registry.register("docs", "https://example.com/1"));
                    assertFalse(registry.register("docs", "https://example.com/2"));
                    assertEquals("https://example.com/1", registry.resolve("docs"));
                }

                @Test
                void distinctAliasesAreIndependent() {
                    assertTrue(registry.register("one", "https://example.com/1"));
                    assertTrue(registry.register("two", "https://example.com/2"));
                    assertEquals("https://example.com/2", registry.resolve("two"));
                }

                @Test
                void nullAliasIsRejected() {
                    assertThrows(NullPointerException.class, () -> registry.register(null, "https://example.com"));
                }
            }
            """;

    private static final Map<String, String> BY_KEY = Map.of(
            "qr-code/main", QR_MAIN,
            "qr-code/test", QR_TEST,
            "idle-expiry/main", IDLE_MAIN,
            "idle-expiry/test", IDLE_TEST,
            "alias/legacy", ALIAS_LEGACY,
            "alias/regression-test", ALIAS_REGRESSION_TEST,
            "alias/fixed", ALIAS_FIXED,
            "alias/refactored", ALIAS_REFACTORED,
            "alias/edge-test", ALIAS_EDGE_TEST);

    /**
     * Looks up a template.
     *
     * @param key template key such as {@code qr-code/main}
     * @return the Java source
     * @throws IllegalArgumentException when no template exists
     */
    public static String get(String key) {
        String source = BY_KEY.get(key);
        if (source == null) {
            throw new IllegalArgumentException("no code template for " + key);
        }
        return source;
    }
}
