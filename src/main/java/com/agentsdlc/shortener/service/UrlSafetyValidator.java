package com.agentsdlc.shortener.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rejects URLs that are malformed or that point into private infrastructure
 * (SSRF protection).
 *
 * <p>Checks, in order: length, syntax, {@code http}/{@code https} scheme,
 * no embedded credentials, internal host names, obfuscated numeric IP forms
 * ({@code 2130706433}, {@code 0x7f.1}, {@code 127.1}) and finally the address
 * class of IP literals (loopback, private, link-local incl. cloud metadata,
 * CGNAT, multicast, unique-local IPv6, IPv4-mapped IPv6).</p>
 *
 * <p>Deliberately no DNS lookup: the runtime path must work offline and a
 * resolve-then-check here is defeated by DNS rebinding anyway. Anything that
 * later <em>fetches</em> a stored URL must re-check the resolved address at
 * connect time; this validator is the first line, not the only one.</p>
 */
@Component
public class UrlSafetyValidator {

    /** Maximum accepted URL length. */
    public static final int MAX_URL_LENGTH = 2048;

    private static final Pattern DOTTED_QUAD =
            Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern NUMERIC_HOST = Pattern.compile("^(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*$");
    private static final List<String> BLOCKED_SUFFIXES =
            List.of(".localhost", ".local", ".internal", ".lan", ".home.arpa");

    /** Creates the validator; it is stateless. */
    public UrlSafetyValidator() {
        // Stateless component.
    }

    /**
     * Validates and normalises a URL.
     *
     * @param raw the URL as submitted by the client
     * @return the URL, trimmed, safe to store
     * @throws ShortenerException with status 400 when the URL is rejected
     */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ShortenerException.badRequest("url is required");
        }
        String url = raw.trim();
        if (url.length() > MAX_URL_LENGTH) {
            throw ShortenerException.badRequest("url longer than " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw ShortenerException.badRequest("url is not syntactically valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw ShortenerException.badRequest("only http and https urls are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw ShortenerException.badRequest("urls with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ShortenerException.badRequest("url must have a valid host");
        }
        checkHost(host.toLowerCase(Locale.ROOT));
        return url;
    }

    private void checkHost(String hostIn) {
        String host = hostIn.endsWith(".") ? hostIn.substring(0, hostIn.length() - 1) : hostIn;
        if (host.equals("localhost") || BLOCKED_SUFFIXES.stream().anyMatch(host::endsWith)) {
            throw ShortenerException.badRequest("url targets an internal host");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            checkAddress(literal(host.substring(1, host.length() - 1)));
            return;
        }
        if (DOTTED_QUAD.matcher(host).matches()) {
            checkAddress(literal(host));
            return;
        }
        if (NUMERIC_HOST.matcher(host).matches()) {
            // Decimal, hex, octal or short-form IPv4 — browsers resolve these to real addresses.
            throw ShortenerException.badRequest("obfuscated ip literals are not allowed");
        }
    }

    private static InetAddress literal(String ip) {
        try {
            // For an IP literal getByName only parses; it performs no DNS lookup.
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw ShortenerException.badRequest("url has an invalid ip address");
        }
    }

    private static void checkAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || isOtherReserved(address)) {
            throw ShortenerException.badRequest("url targets a private or reserved network");
        }
    }

    private static boolean isOtherReserved(InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc; // fc00::/7 unique-local
        }
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 0                                   // 0.0.0.0/8 "this network"
                || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 CGNAT
                || first >= 240;                            // 240.0.0.0/4 reserved + broadcast
    }
}
