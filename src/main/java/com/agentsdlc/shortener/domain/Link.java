package com.agentsdlc.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * A short link. Rows are written once and never updated: clicks live in
 * their own table so analytics cannot contend with, or corrupt, the link.
 *
 * <p>The entity implements {@link Persistable} because its id is assigned by
 * the application. Without it, Spring Data's {@code save()} would see a
 * non-null id, call {@code merge()}, and silently <em>overwrite</em> an
 * existing link that holds the same custom alias — the exact race fixed in
 * the brownfield scenario. With {@code isNew() == true} the insert goes
 * through {@code persist()} and the primary key rejects the duplicate.</p>
 */
@Entity
@Table(name = "links")
public class Link implements Persistable<String> {

    @Id
    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "target_url", length = 2048, nullable = false)
    private String targetUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "custom_alias", nullable = false)
    private boolean customAlias;

    @Transient
    private boolean isNew = true;

    /** Required by JPA; not for application use. */
    protected Link() {
    }

    /**
     * Creates a new, not-yet-persisted link.
     *
     * @param code        the short code (generated or custom alias)
     * @param targetUrl   the validated destination URL
     * @param createdAt   creation instant
     * @param expiresAt   expiry instant, or {@code null} for no expiry
     * @param customAlias whether {@code code} was supplied by the client
     */
    public Link(String code, String targetUrl, Instant createdAt, Instant expiresAt, boolean customAlias) {
        this.code = code;
        this.targetUrl = targetUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.customAlias = customAlias;
    }

    /**
     * Whether the link has expired at the given instant.
     *
     * @param now the current instant
     * @return {@code true} when an expiry exists and {@code now} is at or past it
     */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return code;
    }

    @Override
    public boolean isNew() {
        return isNew;
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
     * Returns the destination URL.
     *
     * @return the destination URL
     */
    public String getTargetUrl() {
        return targetUrl;
    }

    /**
     * Returns the creation instant.
     *
     * @return the creation instant
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the expiry instant.
     *
     * @return the expiry instant, or {@code null} if the link never expires
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Returns whether the code is a client-chosen alias.
     *
     * @return {@code true} for a custom alias
     */
    public boolean isCustomAlias() {
        return customAlias;
    }
}
