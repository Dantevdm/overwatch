package com.overwatch.api.repository;

import com.overwatch.common.persistence.CardholderSummaryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads {@code cardholder_summary}. See V7 for why it is a view. */
public interface CardholderSummaryRepository
        extends JpaRepository<CardholderSummaryEntity, String> {

    /**
     * The directory: most active first, optionally filtered by name or reference.
     *
     * <p>The same query the service used to run against {@code transactions},
     * minus the aggregation — which is the entire point of the view. It reads a
     * page of a few thousand rows instead of grouping half a million, and the
     * ORDER BY is an index rather than a sort.
     *
     * <p>{@code ESCAPE} is declared explicitly for the same reason it is on
     * {@link TransactionReadRepository#searchCustomers}: Hibernate otherwise
     * emits {@code escape ''}, which makes the escaping inert and lets a typed
     * underscore match any character.
     */
    @Query("""
            SELECT c FROM CardholderSummaryEntity c
            WHERE LOWER(c.customerName) LIKE :pattern ESCAPE '\\'
               OR LOWER(c.customerId)   LIKE :pattern ESCAPE '\\'
            ORDER BY c.transactions DESC
            """)
    Page<CardholderSummaryEntity> search(@Param("pattern") String pattern, Pageable pageable);
}
