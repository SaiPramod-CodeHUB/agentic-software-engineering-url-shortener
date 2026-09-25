package com.agentsdlc.shortener.service;

import com.agentsdlc.shortener.config.ShortenerProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pseudonymises client IP addresses with HMAC-SHA256.
 *
 * <p>A plain SHA-256 of an IPv4 address is trivially reversible by brute
 * force (2<sup>32</sup> inputs); a keyed HMAC is not, as long as the key stays
 * secret. The result is stable, so it still supports unique-visitor counts
 * and rate-limit keys without the service ever storing or logging an IP.</p>
 */
@Component
public class VisitorHasher {

    private final SecretKeySpec key;

    /**
     * Creates the hasher.
     *
     * @param properties supplies the HMAC key
     */
    @Autowired
    public VisitorHasher(ShortenerProperties properties) {
        this(properties.visitorHashKey());
    }

    /**
     * Creates the hasher with an explicit key.
     *
     * @param secret the HMAC key; must not be blank
     */
    public VisitorHasher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("visitor hash key must be configured");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * Hashes a client address.
     *
     * @param clientAddress the remote address (never persisted)
     * @return 64 hex characters
     */
    public String hash(String clientAddress) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(String.valueOf(clientAddress).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
