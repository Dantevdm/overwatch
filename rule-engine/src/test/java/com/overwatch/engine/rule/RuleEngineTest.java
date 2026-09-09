package com.overwatch.engine.rule;

import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Evaluation;
import com.overwatch.common.domain.RuleState;
import com.overwatch.common.domain.Severity;
import com.overwatch.common.domain.Transaction;
import com.overwatch.engine.rule.impl.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleEngine")
class RuleEngineTest {

    private static final double DELTA = 1e-9;

    private final RuleEngine engine = new RuleEngine(List.of(
            new HighValueRule(), new VelocityRule(), new LateNightRule(),
            new RoundAmountRule(), new CrossBorderRule(), new CategoryWatchlistRule(),
            new AmountDeviationRule(), new ExplodingRule()));

    /** Always throws — proves one broken rule cannot stop the others. */
    static final class ExplodingRule implements FraudRule {
        @Override public String ruleType() { return "EXPLODES"; }
        @Override public Optional<RuleFinding> evaluate(RuleContext c) {
            throw new IllegalStateException("deliberate failure");
        }
    }

    static final TransactionHistory NO_HISTORY = new TransactionHistory() {
        @Override public long countRecent(String c, Duration w) { return 0; }
        @Override public Optional<BigDecimal> averageAmount(String c, int m) { return Optional.empty(); }
    };

    private static RuleConfig cfg(long id, String type, RuleState state, double weight,
                                  Map<String, Object> params) {
        return new RuleConfig(id, type, type, state, weight, RuleParameters.of(params));
    }

    /** 02:30 SAST, R60 000 exactly, crypto merchant, acquired in Nigeria. */
    private static Transaction suspicious() {
        return new Transaction(UUID.randomUUID(), "card-9", new BigDecimal("60000.00"), "ZAR",
                "Luno Crypto Exchange", "crypto", "NG", Channel.ONLINE,
                Instant.parse("2026-09-09T00:30:00Z"), Map.of());
    }

    private static Transaction ordinary() {
        return new Transaction(UUID.randomUUID(), "card-1", new BigDecimal("342.75"), "ZAR",
                "Checkers Brackenfell", "groceries", "ZA", Channel.POS,
                Instant.parse("2026-09-09T10:15:00Z"), Map.of());
    }

    private static final List<RuleConfig> LIVE_RULES = List.of(
            cfg(1, "HIGH_VALUE", RuleState.ENABLED, 0.40, Map.of("threshold", 50000)),
            cfg(3, "CROSS_BORDER", RuleState.ENABLED, 0.30, Map.of()),
            cfg(4, "CATEGORY_WATCHLIST", RuleState.ENABLED, 0.25, Map.of()),
            cfg(5, "LATE_NIGHT", RuleState.ENABLED, 0.20, Map.of()),
            cfg(6, "ROUND_AMOUNT", RuleState.ENABLED, 0.15, Map.of()));

    @Test
    @DisplayName("an ordinary transaction produces no hits and no alert")
    void ordinaryTransactionIsClean() {
        Evaluation e = engine.evaluate(ordinary(), LIVE_RULES, NO_HISTORY);

        assertEquals(0, e.scoringHits().size());
        assertEquals(0.0, e.riskScore(), DELTA);
        assertFalse(e.isAlert());
    }

    @Test
    @DisplayName("a suspicious transaction accumulates hits and caps at 1.0")
    void suspiciousTransactionAlerts() {
        Evaluation e = engine.evaluate(suspicious(), LIVE_RULES, NO_HISTORY);

        assertEquals(5, e.scoringHits().size());
        assertEquals(1.0, e.riskScore(), DELTA);   // weights sum to 1.30, capped
        assertEquals(Severity.CRITICAL, e.severity());
        assertTrue(e.isAlert());
        // Every hit must explain itself; a reason is what the analyst acts on.
        assertTrue(e.scoringHits().stream().allMatch(h -> !h.reason().isBlank()));
    }

    @Test
    @DisplayName("a SHADOW rule cannot influence the score or raise an alert")
    void shadowRuleIsIsolated() {
        // The shadow rule carries a crushing 0.90 weight and definitely fires.
        // If any of it leaked into the score, this transaction would alert on the
        // strength of a rule that is explicitly still being evaluated.
        List<RuleConfig> configs = List.of(
                cfg(5, "LATE_NIGHT", RuleState.ENABLED, 0.20, Map.of()),
                cfg(1, "HIGH_VALUE", RuleState.SHADOW, 0.90, Map.of("threshold", 50000)));

        Evaluation e = engine.evaluate(suspicious(), configs, NO_HISTORY);

        assertEquals(1, e.scoringHits().size());
        assertEquals(1, e.shadowHits().size());
        assertEquals(0.20, e.riskScore(), DELTA);
        assertEquals(Severity.LOW, e.severity());
        assertFalse(e.isAlert());
        assertTrue(e.shadowHits().get(0).shadow());
    }

    @Test
    @DisplayName("a DISABLED rule is not evaluated at all")
    void disabledRuleIsSkipped() {
        Evaluation e = engine.evaluate(suspicious(), List.of(
                cfg(1, "HIGH_VALUE", RuleState.DISABLED, 0.40, Map.of("threshold", 50000))),
                NO_HISTORY);

        assertEquals(0, e.scoringHits().size());
        assertEquals(0, e.shadowHits().size());
    }

    @Test
    @DisplayName("a rule that throws does not stop the others")
    void failingRuleIsContained() {
        // One misconfigured rule must not stop the remaining rules from
        // protecting anyone.
        Evaluation e = engine.evaluate(suspicious(), List.of(
                cfg(99, "EXPLODES", RuleState.ENABLED, 0.50, Map.of()),
                cfg(1, "HIGH_VALUE", RuleState.ENABLED, 0.40, Map.of("threshold", 50000))),
                NO_HISTORY);

        assertEquals(1, e.scoringHits().size());
        assertEquals(0.40, e.riskScore(), DELTA);
    }

    @Test
    @DisplayName("configuration naming an unimplemented rule type is skipped safely")
    void unknownRuleTypeIsSkipped() {
        Evaluation e = engine.evaluate(suspicious(), List.of(
                cfg(50, "NOT_IMPLEMENTED_YET", RuleState.ENABLED, 0.5, Map.of())),
                NO_HISTORY);

        assertEquals(0, e.scoringHits().size());
        assertFalse(e.isAlert());
    }

    @Test
    @DisplayName("weight and shadow come from configuration, not the rule")
    void configurationOwnsWeighting() {
        Evaluation heavy = engine.evaluate(suspicious(), List.of(
                cfg(5, "LATE_NIGHT", RuleState.ENABLED, 0.75, Map.of())), NO_HISTORY);
        Evaluation light = engine.evaluate(suspicious(), List.of(
                cfg(5, "LATE_NIGHT", RuleState.ENABLED, 0.10, Map.of())), NO_HISTORY);

        // Same rule, same transaction, different configured weight.
        assertEquals(0.75, heavy.riskScore(), DELTA);
        assertEquals(0.10, light.riskScore(), DELTA);
        assertEquals(Severity.HIGH, heavy.severity());
        assertEquals(Severity.LOW, light.severity());
    }
}
