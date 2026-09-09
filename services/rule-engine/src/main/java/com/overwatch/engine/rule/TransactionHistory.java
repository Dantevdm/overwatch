package com.overwatch.engine.rule;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * The lookups rules need against past activity on a card.
 *
 * <p>A port, not a repository. Rules depend on this interface rather than on JPA,
 * which keeps them free of Spring and makes them testable with a hand-written fake
 * instead of a database.
 */
public interface TransactionHistory {

    /**
     * How many transactions this card has had within the window, counting the one
     * being evaluated. Including it is intentional: an analyst reading "6 in 8
     * minutes" expects that count to describe what actually happened.
     */
    long countRecent(String cardId, Duration window);

    /**
     * Mean transaction amount for this card, or empty when there is less than
     * {@code minimumSamples} of history — a baseline built from three transactions
     * is not a baseline, and acting on one produces false positives on new cards.
     */
    Optional<BigDecimal> averageAmount(String cardId, int minimumSamples);
}
