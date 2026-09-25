package com.agentsdlc.shortener.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence seam for {@link Click}. Aggregations are portable JPQL so the
 * database can be swapped without rewriting analytics.
 */
public interface ClickRepository extends JpaRepository<Click, Long> {

    /**
     * Counts clicks for a code.
     *
     * @param code the short code
     * @return total clicks
     */
    long countByCode(String code);

    /**
     * Counts distinct visitor hashes for a code.
     *
     * @param code the short code
     * @return unique visitors
     */
    @Query("select count(distinct c.visitorHash) from Click c where c.code = :code")
    long countUniqueVisitors(@Param("code") String code);

    /**
     * Groups clicks by referrer host.
     *
     * @param code the short code
     * @return rows of {@code [referrer, count]}, most frequent first
     */
    @Query("select c.referrer, count(c) from Click c where c.code = :code "
            + "group by c.referrer order by count(c) desc, c.referrer asc")
    List<Object[]> referrerBreakdown(@Param("code") String code);

    /**
     * Returns the most recent clicks for a code.
     *
     * @param code     the short code
     * @param pageable limits the number of rows
     * @return clicks, newest first
     */
    List<Click> findByCodeOrderByClickedAtDescIdDesc(String code, Pageable pageable);
}
