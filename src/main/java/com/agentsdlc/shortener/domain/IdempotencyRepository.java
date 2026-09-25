package com.agentsdlc.shortener.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence seam for {@link IdempotencyRecord}. */
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {
}
