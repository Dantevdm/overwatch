package com.overwatch.engine.rule.impl;

import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Transaction;
import com.overwatch.engine.rule.RuleContext;
import com.overwatch.engine.rule.RuleParameters;
import com.overwatch.engine.rule.TransactionHistory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Shared fixtures. Rules take a context, so building one is the whole setup. */
final class RuleTestSupport {

    private RuleTestSupport() {
    }

    static Transaction txn(String amount, String category, String country, String instant) {
        return new Transaction(UUID.randomUUID(), "card-1", new BigDecimal(amount), "ZAR",
                "Checkers Brackenfell", category, country, Channel.POS,
                Instant.parse(instant), Map.of());
    }

    static RuleContext ctx(Transaction t, Map<String, Object> params, TransactionHistory history) {
        return new RuleContext(t, RuleParameters.of(params), history);
    }

    static RuleContext ctx(Transaction t, Map<String, Object> params) {
        return ctx(t, params, NO_HISTORY);
    }

    /** Card with no past activity. */
    static final TransactionHistory NO_HISTORY = new FakeHistory(0, null, 0);

    /** Hand-written stand-in — a rule needs no database to be tested. */
    record FakeHistory(long count, BigDecimal average, int samples) implements TransactionHistory {
        @Override
        public long countRecent(String cardId, Duration window) {
            return count;
        }

        @Override
        public Optional<BigDecimal> averageAmount(String cardId, int minimumSamples) {
            return samples >= minimumSamples ? Optional.ofNullable(average) : Optional.empty();
        }
    }

    static TransactionHistory withCount(long count) {
        return new FakeHistory(count, null, 0);
    }

    static TransactionHistory withAverage(String average, int samples) {
        return new FakeHistory(0, new BigDecimal(average), samples);
    }
}
