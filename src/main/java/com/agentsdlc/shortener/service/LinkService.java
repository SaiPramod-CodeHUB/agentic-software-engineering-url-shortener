package com.agentsdlc.shortener.service;

import com.agentsdlc.shortener.config.ShortenerProperties;
import com.agentsdlc.shortener.domain.Click;
import com.agentsdlc.shortener.domain.ClickRepository;
import com.agentsdlc.shortener.domain.IdempotencyRecord;
import com.agentsdlc.shortener.domain.IdempotencyRepository;
import com.agentsdlc.shortener.domain.Link;
import com.agentsdlc.shortener.domain.LinkRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Core use cases: create a link, resolve (and count) a click, report stats.
 *
 * <p>Correctness under concurrency relies on primary keys, not on
 * check-then-act: aliases and idempotency keys are inserted, and a constraint
 * violation decides the loser. Transactions are explicit
 * ({@link TransactionTemplate}) because the retry/replay decision must happen
 * <em>after</em> a failed transaction has rolled back, which a single
 * {@code @Transactional} method cannot express.</p>
 */
@Service
public class LinkService {

    /** Allowed shape of a custom alias. */
    public static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    /** Aliases that would shadow API routes. */
    public static final Set<String> RESERVED_ALIASES = Set.of("health", "stats", "shorten", "error", "actuator");

    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final int RECENT_CLICKS = 10;

    private final LinkRepository links;
    private final ClickRepository clicks;
    private final IdempotencyRepository idempotency;
    private final UrlSafetyValidator urlValidator;
    private final CodeGenerator codes;
    private final VisitorHasher visitorHasher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ShortenerProperties properties;

    /**
     * Creates the service; all collaborators are injected.
     *
     * @param links         link repository
     * @param clicks        click repository
     * @param idempotency   idempotency-key repository
     * @param urlValidator  SSRF / syntax validator
     * @param codes         short-code generator
     * @param visitorHasher IP pseudonymiser
     * @param txManager     transaction manager backing the explicit transactions
     * @param clock         time source
     * @param properties    service configuration
     */
    public LinkService(LinkRepository links, ClickRepository clicks, IdempotencyRepository idempotency,
                       UrlSafetyValidator urlValidator, CodeGenerator codes, VisitorHasher visitorHasher,
                       PlatformTransactionManager txManager, Clock clock, ShortenerProperties properties) {
        this.links = links;
        this.clicks = clicks;
        this.idempotency = idempotency;
        this.urlValidator = urlValidator;
        this.codes = codes;
        this.visitorHasher = visitorHasher;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Input to {@link #shorten(ShortenCommand)}.
     *
     * @param url            destination URL
     * @param customAlias    optional client-chosen code
     * @param ttlSeconds     optional lifetime in seconds
     * @param idempotencyKey optional {@code Idempotency-Key} header value
     */
    public record ShortenCommand(String url, String customAlias, Long ttlSeconds, String idempotencyKey) {
    }

    /**
     * Outcome of {@link #shorten(ShortenCommand)}.
     *
     * @param code    the short code
     * @param created {@code true} for a new link, {@code false} for an idempotent replay
     */
    public record ShortenResult(String code, boolean created) {
    }

    /**
     * One entry of the recent-click list.
     *
     * @param clickedAt when the redirect happened
     * @param referrer  referrer host or {@code "direct"}
     */
    public record RecentClick(Instant clickedAt, String referrer) {
    }

    /**
     * Analytics for one link.
     *
     * @param code           the short code
     * @param targetUrl      the destination
     * @param createdAt      creation instant
     * @param expiresAt      expiry instant or {@code null}
     * @param totalClicks    number of redirects served
     * @param uniqueVisitors distinct pseudonymous visitors
     * @param referrers      clicks per referrer host, most frequent first
     * @param recentClicks   latest clicks, newest first
     */
    public record LinkStats(String code, String targetUrl, Instant createdAt, Instant expiresAt,
                            long totalClicks, long uniqueVisitors, Map<String, Long> referrers,
                            List<RecentClick> recentClicks) {
    }

    /**
     * Creates a short link, or replays the result of an earlier request that
     * used the same idempotency key.
     *
     * @param cmd the request
     * @return the code and whether it was newly created
     * @throws ShortenerException 400 for invalid input, 409 for a taken alias,
     *                            422 for a reused key with a different body
     */
    public ShortenResult shorten(ShortenCommand cmd) {
        String url = urlValidator.validate(cmd.url());
        String alias = validateAlias(cmd.customAlias());
        Long ttl = validateTtl(cmd.ttlSeconds());
        String key = validateKey(cmd.idempotencyKey());
        String requestHash = sha256(url + "\n" + alias + "\n" + ttl);

        if (key != null) {
            Optional<ShortenResult> replay = replay(key, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = alias != null ? alias : codes.next();
            try {
                tx.executeWithoutResult(status -> insert(code, url, ttl, alias != null, key, requestHash));
                return new ShortenResult(code, true);
            } catch (DataIntegrityViolationException conflict) {
                // Either a concurrent request with our idempotency key won, the alias is taken,
                // or (astronomically rarely) a random code collided. Decide which, in that order.
                if (key != null) {
                    Optional<ShortenResult> replay = replay(key, requestHash);
                    if (replay.isPresent()) {
                        return replay.get();
                    }
                }
                if (alias != null) {
                    throw ShortenerException.aliasTaken();
                }
            }
        }
        throw new IllegalStateException("could not allocate a unique code after " + MAX_CODE_ATTEMPTS + " attempts");
    }

    /**
     * Resolves a code to its destination and records the click.
     *
     * @param code          the short code
     * @param referrer      raw {@code Referer} header, may be {@code null}
     * @param clientAddress remote address; hashed, never stored
     * @return the destination URL
     * @throws ShortenerException 404 when unknown, 410 when expired
     */
    public String resolve(String code, String referrer, String clientAddress) {
        Instant now = clock.instant();
        Link link = links.findById(code).orElseThrow(ShortenerException::notFound);
        if (link.isExpiredAt(now)) {
            throw ShortenerException.gone();
        }
        // Insert-only: recording a click never touches the link row.
        clicks.save(new Click(code, now, referrerHost(referrer), visitorHasher.hash(clientAddress)));
        return link.getTargetUrl();
    }

    /**
     * Returns analytics for a link (also available after expiry).
     *
     * @param code the short code
     * @return click analytics
     * @throws ShortenerException 404 when unknown
     */
    public LinkStats stats(String code) {
        Link link = links.findById(code).orElseThrow(ShortenerException::notFound);
        Map<String, Long> referrers = new LinkedHashMap<>();
        for (Object[] row : clicks.referrerBreakdown(code)) {
            referrers.put((String) row[0], (Long) row[1]);
        }
        List<RecentClick> recent = clicks
                .findByCodeOrderByClickedAtDescIdDesc(code, PageRequest.of(0, RECENT_CLICKS)).stream()
                .map(c -> new RecentClick(c.getClickedAt(), c.getReferrer()))
                .toList();
        return new LinkStats(link.getCode(), link.getTargetUrl(), link.getCreatedAt(), link.getExpiresAt(),
                clicks.countByCode(code), clicks.countUniqueVisitors(code), referrers, recent);
    }

    /**
     * Builds the public short URL for a code.
     *
     * @param code the short code
     * @return absolute short URL
     */
    public String shortUrl(String code) {
        String base = properties.baseUrl();
        return (base.endsWith("/") ? base : base + "/") + code;
    }

    private void insert(String code, String url, Long ttl, boolean custom, String key, String requestHash) {
        Instant now = clock.instant();
        Instant expiresAt = ttl == null ? null : now.plusSeconds(ttl);
        links.saveAndFlush(new Link(code, url, now, expiresAt, custom));
        if (key != null) {
            idempotency.saveAndFlush(new IdempotencyRecord(key, requestHash, code, now));
        }
    }

    private Optional<ShortenResult> replay(String key, String requestHash) {
        return idempotency.findById(key).map(existing -> {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw ShortenerException.idempotencyMismatch();
            }
            return new ShortenResult(existing.getCode(), false);
        });
    }

    private static String validateAlias(String alias) {
        if (alias == null || alias.isEmpty()) {
            return null;
        }
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw ShortenerException.badRequest("customAlias must match " + ALIAS_PATTERN.pattern());
        }
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw ShortenerException.badRequest("customAlias is reserved");
        }
        return alias;
    }

    private Long validateTtl(Long ttl) {
        if (ttl == null) {
            return null;
        }
        if (ttl <= 0 || ttl > properties.maxTtlSeconds()) {
            throw ShortenerException.badRequest("ttlSeconds must be between 1 and " + properties.maxTtlSeconds());
        }
        return ttl;
    }

    private static String validateKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() > 128) {
            throw ShortenerException.badRequest("Idempotency-Key longer than 128 characters");
        }
        return key;
    }

    private static String referrerHost(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return "direct";
        }
        try {
            String host = URI.create(referrer.trim()).getHost();
            if (host == null) {
                return "unknown";
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.length() > 255 ? lower.substring(0, 255) : lower;
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
