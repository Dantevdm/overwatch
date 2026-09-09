package com.overwatch.engine.rule;

import com.overwatch.common.domain.Transaction;

/**
 * Everything a rule is allowed to see: the transaction under evaluation, its own
 * configured parameters, and a read-only view of card history.
 *
 * <p>A rule gets no repository, no Spring context and no clock of its own — the
 * clock is passed in so late-night behaviour can be tested without waiting until
 * two in the morning.
 */
public record RuleContext(
        Transaction transaction,
        RuleParameters parameters,
        TransactionHistory history
) {
}
