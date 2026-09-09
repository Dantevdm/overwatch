package com.overwatch.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Evaluation")
class EvaluationTest {

    private static final double DELTA = 1e-9;

    private static Transaction txn() {
        return new Transaction(
                UUID.randomUUID(), "card-4821", new BigDecimal("52340.00"), "ZAR",
                "Takealot Online", "ecommerce", "ZA", Channel.ONLINE,
                Instant.parse("2026-09-09T02:14:00Z"), Map.of());
    }

    private static RuleHit hit(String type, double weight, boolean shadow) {
        return new RuleHit(type, 1L, weight, "because", Map.of(), shadow);
    }

    @Nested
    @DisplayName("scoring")
    class Scoring {

        @Test
        @DisplayName("sums the weights of scoring hits")
        void sumsWeights() {
            Evaluation e = Evaluation.of(txn(), List.of(
                    hit("LATE_NIGHT", 0.20, false),
                    hit("ROUND_AMOUNT", 0.15, false)));

            assertEquals(0.35, e.riskScore(), DELTA);
            assertEquals(Severity.MEDIUM, e.severity());
        }

        @Test
        @DisplayName("caps the score at 1.0 when weights over-sum")
        void capsAtOne() {
            Evaluation e = Evaluation.of(txn(), List.of(
                    hit("A", 0.40, false), hit("B", 0.35, false),
                    hit("C", 0.30, false), hit("D", 0.25, false)));

            // Weights total 1.30. A score above 1.0 would break every downstream
            // percentage and the risk_score CHECK constraint in the database.
            assertEquals(1.0, e.riskScore(), DELTA);
            assertEquals(Severity.CRITICAL, e.severity());
        }

        @Test
        @DisplayName("a transaction tripping nothing scores zero and is not an alert")
        void cleanTransaction() {
            Evaluation e = Evaluation.of(txn(), List.of());

            assertEquals(0.0, e.riskScore(), DELTA);
            assertEquals(Severity.LOW, e.severity());
            assertFalse(e.isAlert());
            assertTrue(e.scoringHits().isEmpty());
            assertTrue(e.shadowHits().isEmpty());
        }
    }

    @Nested
    @DisplayName("shadow isolation")
    class ShadowIsolation {

        @Test
        @DisplayName("shadow hits are separated from scoring hits")
        void separatesHits() {
            Evaluation e = Evaluation.of(txn(), List.of(
                    hit("LATE_NIGHT", 0.20, false),
                    hit("ROUND_AMOUNT", 0.15, false),
                    hit("AMOUNT_DEVIATION", 0.90, true)));

            assertEquals(2, e.scoringHits().size());
            assertEquals(1, e.shadowHits().size());
            assertEquals("AMOUNT_DEVIATION", e.shadowHits().get(0).ruleType());
        }

        @Test
        @DisplayName("a shadow hit never contributes to the risk score")
        void shadowDoesNotScore() {
            Evaluation e = Evaluation.of(txn(), List.of(
                    hit("LATE_NIGHT", 0.20, false),
                    hit("ROUND_AMOUNT", 0.15, false),
                    hit("AMOUNT_DEVIATION", 0.90, true)));

            // 0.20 + 0.15 only. If the shadow weight leaked in, the score would
            // be 1.0 and this transaction would page an analyst because of a rule
            // that is explicitly still under evaluation.
            assertEquals(0.35, e.riskScore(), DELTA);
        }

        @Test
        @DisplayName("a heavy shadow-only hit raises no alert")
        void shadowOnlyRaisesNothing() {
            Evaluation e = Evaluation.of(txn(), List.of(hit("EXPERIMENTAL", 0.95, true)));

            assertEquals(0.0, e.riskScore(), DELTA);
            assertEquals(Severity.LOW, e.severity());
            assertFalse(e.isAlert());
            assertEquals(1, e.shadowHits().size());
        }
    }

    @Nested
    @DisplayName("alert threshold")
    class AlertThreshold {

        @Test
        @DisplayName("scores at or above the threshold are alerts")
        void atOrAboveThreshold() {
            assertTrue(Evaluation.of(txn(), List.of(hit("X", Evaluation.ALERT_THRESHOLD, false))).isAlert());
            assertTrue(Evaluation.of(txn(), List.of(hit("X", 0.75, false))).isAlert());
        }

        @Test
        @DisplayName("scores below the threshold are recorded but not alerts")
        void belowThreshold() {
            Evaluation e = Evaluation.of(txn(), List.of(hit("LATE_NIGHT", 0.20, false)));

            assertFalse(e.isAlert());
            // The hit is still retained — "we looked and cleared it" is data too.
            assertEquals(1, e.scoringHits().size());
        }
    }

    @Test
    @DisplayName("retains the originating transaction")
    void retainsTransaction() {
        Transaction t = txn();
        assertEquals(t, Evaluation.of(t, List.of()).transaction());
    }
}
