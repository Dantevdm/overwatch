package com.overwatch.engine.rule.impl;

import com.overwatch.engine.rule.RuleFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static com.overwatch.engine.rule.impl.RuleTestSupport.ctx;
import static com.overwatch.engine.rule.impl.RuleTestSupport.txn;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LateNightRule")
class LateNightRuleTest {

    private final LateNightRule rule = new LateNightRule();

    // Transactions are stored in UTC; South Africa is UTC+2. Every case here
    // would give the wrong answer if the rule compared UTC hours directly, which
    // is the single most likely way for a rule like this to be quietly wrong.
    @Test
    @DisplayName("converts UTC to SAST before comparing")
    void convertsToLocalTime() {
        // 00:30 UTC is 02:30 in Johannesburg — inside 01:00-04:59.
        assertTrue(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T00:30:00Z"), Map.of()))
                .isPresent());

        // 22:00 UTC is midnight SAST — outside.
        assertFalse(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T22:00:00Z"), Map.of()))
                .isPresent());

        // 23:30 UTC is 01:30 SAST the following day — inside, and the case a naive
        // same-day comparison gets wrong.
        assertTrue(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-08T23:30:00Z"), Map.of()))
                .isPresent());
    }

    @Test
    @DisplayName("both bounds are inclusive whole hours")
    void boundsAreInclusive() {
        // 02:30 UTC = 04:30 SAST. endHour 4 covers all of hour 4.
        Optional<RuleFinding> hit =
                rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T02:30:00Z"), Map.of()));
        assertTrue(hit.isPresent());
        assertTrue(hit.get().reason().contains("01:00-04:59"),
                "reason must state the real window, got: " + hit.get().reason());
    }

    @Test
    @DisplayName("handles a window that wraps past midnight")
    void wrapsMidnight() {
        Map<String, Object> overnight = Map.of("startHour", 22, "endHour", 3);

        // 21:00 UTC = 23:00 SAST — inside.
        assertTrue(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T21:00:00Z"), overnight))
                .isPresent());
        // 00:00 UTC = 02:00 SAST — inside, on the far side of midnight.
        assertTrue(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T00:00:00Z"), overnight))
                .isPresent());
        // 10:00 UTC = 12:00 SAST — outside.
        assertFalse(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T10:00:00Z"), overnight))
                .isPresent());
    }

    @Test
    @DisplayName("falls back to SAST when the configured zone is nonsense")
    void unknownZoneFallsBack() {
        // A bad configuration value must not throw on the consumer thread.
        assertTrue(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T00:30:00Z"),
                Map.of("timezone", "Mars/Olympus"))).isPresent());
    }

    @Test
    @DisplayName("is silent outside the window")
    void silentDuringTheDay() {
        assertFalse(rule.evaluate(ctx(txn("100", "retail", "ZA", "2026-09-09T10:00:00Z"), Map.of()))
                .isPresent());
    }
}
