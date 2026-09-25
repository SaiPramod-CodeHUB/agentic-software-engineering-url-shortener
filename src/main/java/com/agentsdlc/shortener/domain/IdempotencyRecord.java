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
 * Binds an {@code Idempotency-Key} to the code it produced, forever.
 *
 * <p>The key is the primary key, so two concurrent first-time requests with
 * the same key cannot both commit: the loser gets a constraint violation and
 * replays the winner's result. {@link Persistable} forces an insert rather
 * than a merge (see {@link Link} for why that matters).</p>
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idem_key", length = 128, nullable = false)
    private String idemKey;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    /** Required by JPA; not for application use. */
    protected IdempotencyRecord() {
    }

    /**
     * Creates a new record.
     *
     * @param idemKey     client-supplied idempotency key
     * @param requestHash SHA-256 of the canonical request body
     * @param code        the code produced by the first request
     * @param createdAt   creation instant
     */
    public IdempotencyRecord(String idemKey, String requestHash, String code, Instant createdAt) {
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.code = code;
        this.createdAt = createdAt;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return idemKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /**
     * Returns the hash of the request that first used this key.
     *
     * @return the request hash
     */
    public String getRequestHash() {
        return requestHash;
    }

    /**
     * Returns the code bound to this key.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the creation instant.
     *
     * @return the creation instant
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
