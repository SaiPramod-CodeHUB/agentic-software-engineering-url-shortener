package com.agentsdlc.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One redirect event. Append-only: a click is inserted and never modified.
 *
 * <p>For compliance the raw client IP is never stored; {@code visitorHash}
 * is a keyed hash that supports unique-visitor counts without being
 * reversible to an address. The referrer is reduced to its host.</p>
 */
@Entity
@Table(name = "clicks")
public class Click {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;

    @Column(name = "referrer", length = 255, nullable = false)
    private String referrer;

    @Column(name = "visitor_hash", length = 64, nullable = false)
    private String visitorHash;

    /** Required by JPA; not for application use. */
    protected Click() {
    }

    /**
     * Creates a click event.
     *
     * @param code        the short code that was followed
     * @param clickedAt   when the redirect happened
     * @param referrer    referrer host, or {@code "direct"}
     * @param visitorHash keyed hash of the client address
     */
    public Click(String code, Instant clickedAt, String referrer, String visitorHash) {
        this.code = code;
        this.clickedAt = clickedAt;
        this.referrer = referrer;
        this.visitorHash = visitorHash;
    }

    /**
     * Returns the database id.
     *
     * @return the database id
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the short code.
     *
     * @return the short code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the click instant.
     *
     * @return the click instant
     */
    public Instant getClickedAt() {
        return clickedAt;
    }

    /**
     * Returns the referrer host.
     *
     * @return the referrer host or {@code "direct"}
     */
    public String getReferrer() {
        return referrer;
    }

    /**
     * Returns the pseudonymous visitor hash.
     *
     * @return the visitor hash
     */
    public String getVisitorHash() {
        return visitorHash;
    }
}
