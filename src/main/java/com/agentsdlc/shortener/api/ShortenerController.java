package com.agentsdlc.shortener.api;

import com.agentsdlc.shortener.service.LinkService;
import com.agentsdlc.shortener.service.LinkService.LinkStats;
import com.agentsdlc.shortener.service.LinkService.ShortenCommand;
import com.agentsdlc.shortener.service.LinkService.ShortenResult;
import com.agentsdlc.shortener.service.RateLimiter;
import com.agentsdlc.shortener.service.VisitorHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for the shortener. Thin by design: it translates HTTP to
 * service calls and back; all rules live in {@link LinkService}.
 *
 * <p>The contract is documented in {@code docs/openapi.yaml}, and
 * {@code OpenApiContractTest} fails the build if the two drift apart.</p>
 */
@RestController
public class ShortenerController {

    private final LinkService links;
    private final RateLimiter rateLimiter;
    private final VisitorHasher visitorHasher;

    /**
     * Creates the controller.
     *
     * @param links         link use cases
     * @param rateLimiter   per-client limiter for link creation
     * @param visitorHasher pseudonymises the client address used as the limiter key
     */
    public ShortenerController(LinkService links, RateLimiter rateLimiter, VisitorHasher visitorHasher) {
        this.links = links;
        this.rateLimiter = rateLimiter;
        this.visitorHasher = visitorHasher;
    }

    /**
     * Creates a short link.
     *
     * @param body           the request body
     * @param idempotencyKey optional {@code Idempotency-Key} header
     * @param request        servlet request, for the client address
     * @return 201 with the new link, or 200 when replaying an idempotent request
     */
    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest body,
                                                   @RequestHeader(value = "Idempotency-Key", required = false)
                                                   String idempotencyKey,
                                                   HttpServletRequest request) {
        // The limiter key is the hashed address: raw IPs are never held, even in memory maps.
        rateLimiter.acquire(visitorHasher.hash(request.getRemoteAddr()));
        ShortenResult result = links.shorten(
                new ShortenCommand(body.url(), body.customAlias(), body.ttlSeconds(), idempotencyKey));
        String shortUrl = links.shortUrl(result.code());
        ShortenResponse response = new ShortenResponse(result.code(), shortUrl, result.created());
        if (result.created()) {
            return ResponseEntity.created(URI.create(shortUrl)).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Liveness probe.
     *
     * @return {@code {"status":"ok"}}
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * Click analytics for a link.
     *
     * @param code the short code
     * @return the link's statistics
     */
    @GetMapping("/stats/{code}")
    public LinkStats stats(@PathVariable String code) {
        return links.stats(code);
    }

    /**
     * Redirects to the destination and records the click.
     *
     * @param code     the short code
     * @param referrer optional {@code Referer} header
     * @param request  servlet request, for the client address
     * @return 302 with a {@code Location} header
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                         @RequestHeader(value = HttpHeaders.REFERER, required = false) String referrer,
                                         HttpServletRequest request) {
        String target = links.resolve(code, referrer, request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                // Redirects must not be cached, or later clicks would bypass analytics and expiry.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
