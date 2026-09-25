package com.agentsdlc.shortener.service;

import com.agentsdlc.shortener.config.ShortenerProperties;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Generates base62 short codes.
 *
 * <p>Codes are unguessable bearer tokens (knowing one must not reveal
 * others), so production injects a {@code SecureRandom}. A sequential or
 * hash-of-URL scheme would be enumerable. At the default length of 8 there are
 * 62<sup>8</sup> ≈ 2.2 × 10<sup>14</sup> codes (~47 bits); collisions are
 * handled by the caller retrying on the primary key.</p>
 */
@Component
public class CodeGenerator {

    /** The 62 URL-safe characters codes are drawn from. */
    public static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final RandomGenerator random;
    private final int length;

    /**
     * Creates the generator.
     *
     * @param random     random source; {@code SecureRandom} in production, seeded in tests
     * @param properties supplies the code length
     */
    @Autowired
    public CodeGenerator(RandomGenerator random, ShortenerProperties properties) {
        this(random, properties.codeLength());
    }

    /**
     * Creates the generator with an explicit length (used by unit tests).
     *
     * @param random random source
     * @param length number of characters per code; must be between 4 and 32
     */
    public CodeGenerator(RandomGenerator random, int length) {
        if (length < 4 || length > 32) {
            throw new IllegalArgumentException("code length must be 4..32 but was " + length);
        }
        this.random = random;
        this.length = length;
    }

    /**
     * Produces a new random code.
     *
     * @return a base62 string of the configured length
     */
    public String next() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            // nextInt(bound) is unbiased, unlike random.nextInt() % 62.
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
