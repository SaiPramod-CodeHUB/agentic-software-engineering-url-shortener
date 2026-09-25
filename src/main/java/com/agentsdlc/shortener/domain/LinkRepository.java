package com.agentsdlc.shortener.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence seam for {@link Link}. Only standard Spring Data methods are
 * used, so moving from H2 to another relational database needs no code change.
 */
public interface LinkRepository extends JpaRepository<Link, String> {
}
