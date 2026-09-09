package com.overwatch.api.repository;

import com.overwatch.common.persistence.TransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionReadRepository extends JpaRepository<TransactionEntity, UUID> {

    /**
     * Filtered transaction list. A null string parameter means "no filter", which
     * keeps this to one query instead of a Specification tree.
     *
     * <p>{@code since} is deliberately <em>not</em> nullable, and that is load
     * bearing rather than an inconsistency. PostgreSQL infers a parameter's type
     * from the context it appears in, and {@code :since IS NULL} supplies no
     * context at all — the driver sends the parameter untyped and the server
     * rejects the statement outright with "could not determine data type of
     * parameter". The string filters survive the same shape only because an
     * untyped parameter falls back to {@code text}, which happens to compare
     * correctly against a {@code VARCHAR} column. Nothing rescues a timestamp.
     * So the absence of a lower bound is expressed as {@link Instant#EPOCH} by
     * the caller, and the comparison stays unconditional and index-friendly.
     *
     * <p>The rule this leaves behind: never write {@code :param IS NULL} for a
     * parameter that is not a string.
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE (:cardId   IS NULL OR t.cardId = :cardId)
              AND (:category IS NULL OR t.merchantCategory = :category)
              AND t.occurredAt >= :since
            ORDER BY t.occurredAt DESC
            """)
    Page<TransactionEntity> search(@Param("cardId") String cardId,
                                   @Param("category") String category,
                                   @Param("since") Instant since,
                                   Pageable pageable);

    @Query("""
            SELECT t.merchantCategory, COUNT(t) FROM TransactionEntity t
            GROUP BY t.merchantCategory ORDER BY COUNT(t) DESC
            """)
    List<Object[]> countByCategory();

    long countByOccurredAtAfter(Instant since);
}
