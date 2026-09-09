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

    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE (:cardId   IS NULL OR t.cardId = :cardId)
              AND (:category IS NULL OR t.merchantCategory = :category)
              AND (:since    IS NULL OR t.occurredAt >= :since)
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
