package com.overwatch.engine.persistence.repository;

import com.overwatch.engine.persistence.entity.TransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    /**
     * Backs the velocity rule. Hits idx_txn_card_occurred as an index-only scan,
     * which matters because this runs on every single transaction — a sequential
     * scan here would sink the pipeline as the table grows.
     */
    @Query("""
            SELECT COUNT(t) FROM TransactionEntity t
            WHERE t.cardId = :cardId AND t.occurredAt >= :since
            """)
    long countByCardSince(@Param("cardId") String cardId, @Param("since") Instant since);

    /** Backs the amount-deviation rule's baseline. */
    @Query("""
            SELECT AVG(t.amount) FROM TransactionEntity t
            WHERE t.cardId = :cardId
            """)
    Optional<BigDecimal> averageAmountForCard(@Param("cardId") String cardId);

    long countByCardId(String cardId);
}
