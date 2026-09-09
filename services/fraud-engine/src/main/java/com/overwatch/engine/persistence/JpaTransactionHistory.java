package com.overwatch.engine.persistence;

import com.overwatch.engine.persistence.repository.TransactionRepository;
import com.overwatch.engine.rule.TransactionHistory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Database-backed implementation of the history lookups rules need.
 *
 * <p>This is the only place rules touch persistence, and they do so through the
 * {@link TransactionHistory} interface rather than this class — which is what
 * keeps every rule unit-testable without a database.
 */
@Component
public class JpaTransactionHistory implements TransactionHistory {

    private final TransactionRepository transactions;

    public JpaTransactionHistory(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Override
    @Transactional(readOnly = true)
    public long countRecent(String cardId, Duration window) {
        return transactions.countByCardSince(cardId, Instant.now().minus(window));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> averageAmount(String cardId, int minimumSamples) {
        // Check the sample count first: an average over three transactions is not
        // a baseline, and acting on one flags every new cardholder.
        if (transactions.countByCardId(cardId) < minimumSamples) {
            return Optional.empty();
        }
        return transactions.averageAmountForCard(cardId);
    }
}
