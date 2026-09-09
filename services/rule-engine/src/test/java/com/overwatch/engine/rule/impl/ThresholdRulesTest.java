package com.overwatch.engine.rule.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.overwatch.engine.rule.impl.RuleTestSupport.ctx;
import static com.overwatch.engine.rule.impl.RuleTestSupport.txn;
import static com.overwatch.engine.rule.impl.RuleTestSupport.withAverage;
import static com.overwatch.engine.rule.impl.RuleTestSupport.withCount;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Threshold and watchlist rules")
class ThresholdRulesTest {

    @Nested
    @DisplayName("HighValueRule")
    class HighValue {
        private final HighValueRule rule = new HighValueRule();

        @Test
        @DisplayName("fires above the threshold, not at it")
        void thresholdIsExclusive() {
            assertTrue(rule.evaluate(ctx(txn("52340.00", "retail", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
            assertFalse(rule.evaluate(ctx(txn("50000.00", "retail", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
        }

        @Test
        @DisplayName("honours a configured threshold")
        void configurable() {
            assertTrue(rule.evaluate(ctx(txn("31000.00", "retail", "ZA", "2026-09-09T10:00:00Z"),
                    Map.of("threshold", 30000))).isPresent());
        }

        @Test
        @DisplayName("a malformed threshold falls back instead of throwing")
        void malformedParameter() {
            // A bad rule row must not take down the consumer thread.
            assertFalse(rule.evaluate(ctx(txn("100.00", "retail", "ZA", "2026-09-09T10:00:00Z"),
                    Map.of("threshold", "not-a-number"))).isPresent());
        }
    }

    @Nested
    @DisplayName("VelocityRule")
    class Velocity {
        private final VelocityRule rule = new VelocityRule();

        @Test
        @DisplayName("fires above the count, not at it")
        void limitIsInclusive() {
            assertTrue(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T10:00:00Z"),
                    Map.of(), withCount(6))).isPresent());
            assertFalse(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T10:00:00Z"),
                    Map.of(), withCount(5))).isPresent());
        }

        @Test
        @DisplayName("a nonsensical limit makes the rule inert")
        void zeroLimitIsInert() {
            // Rather than flagging literally every transaction.
            assertFalse(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T10:00:00Z"),
                    Map.of("maxCount", 0), withCount(99))).isPresent());
        }
    }

    @Nested
    @DisplayName("RoundAmountRule")
    class RoundAmount {
        private final RoundAmountRule rule = new RoundAmountRule();

        @Test
        @DisplayName("fires on an exact multiple above the floor")
        void exactMultiple() {
            assertTrue(rule.evaluate(ctx(txn("10000.00", "retail", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
        }

        @Test
        @DisplayName("ignores amounts with cents")
        void notRound() {
            assertFalse(rule.evaluate(ctx(txn("10000.50", "retail", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
        }

        @Test
        @DisplayName("ignores round amounts below the floor")
        void belowFloor() {
            // Without the floor this fires on every R1 000 ATM withdrawal.
            assertFalse(rule.evaluate(ctx(txn("1000.00", "retail", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
        }
    }

    @Nested
    @DisplayName("CrossBorderRule")
    class CrossBorder {
        private final CrossBorderRule rule = new CrossBorderRule();

        @Test
        @DisplayName("is silent at home and fires abroad")
        void homeVersusAbroad() {
            assertFalse(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
            assertTrue(rule.evaluate(ctx(txn("100", "retail", "NG", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
        }

        @Test
        @DisplayName("compares country codes case-insensitively")
        void caseInsensitive() {
            // Casing out of a config form must not create a false positive.
            assertFalse(rule.evaluate(ctx(txn("100", "retail", "za", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
        }

        @Test
        @DisplayName("respects an allow-list")
        void allowList() {
            assertFalse(rule.evaluate(ctx(txn("100", "retail", "NA", "2026-09-09T10:00:00Z"),
                    Map.of("allowedCountries", List.of("ZA", "NA")))).isPresent());
        }
    }

    @Nested
    @DisplayName("CategoryWatchlistRule")
    class Watchlist {
        private final CategoryWatchlistRule rule = new CategoryWatchlistRule();

        @Test
        @DisplayName("fires on a watchlisted category, whatever the casing")
        void watchlisted() {
            assertTrue(rule.evaluate(ctx(txn("100", "crypto", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
            assertTrue(rule.evaluate(ctx(txn("100", "CRYPTO", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
        }

        @Test
        @DisplayName("is silent on ordinary categories")
        void ordinary() {
            assertFalse(rule.evaluate(ctx(txn("100", "groceries", "ZA", "2026-09-09T10:00:00Z"), Map.of())).isPresent());
        }
    }

    @Nested
    @DisplayName("AmountDeviationRule")
    class Deviation {
        private final AmountDeviationRule rule = new AmountDeviationRule();

        @Test
        @DisplayName("fires well above the card's baseline")
        void aboveBaseline() {
            assertTrue(rule.evaluate(ctx(txn("9000.00", "retail", "ZA", "2026-09-09T10:00:00Z"),
                    Map.of(), withAverage("800", 40))).isPresent());
        }

        @Test
        @DisplayName("declines to judge without enough history")
        void insufficientHistory() {
            // The same amounts, but three transactions is not a baseline. Firing
            // here would flag every new cardholder's first real purchase.
            assertFalse(rule.evaluate(ctx(txn("9000.00", "retail", "ZA", "2026-09-09T10:00:00Z"),
                    Map.of(), withAverage("800", 3))).isPresent());
        }

        @Test
        @DisplayName("is silent within the multiple")
        void withinRange() {
            assertFalse(rule.evaluate(ctx(txn("3000.00", "retail", "ZA", "2026-09-09T10:00:00Z"),
                    Map.of(), withAverage("800", 40))).isPresent());
        }
    }
}
