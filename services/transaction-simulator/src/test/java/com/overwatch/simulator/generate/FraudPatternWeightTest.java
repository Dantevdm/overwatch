package com.overwatch.simulator.generate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The injection mix, pinned.
 *
 * <p>This exists because getting it wrong is invisible: uniform selection looks
 * perfectly fair in the injection counter and produced an alert list that was
 * 93% one rule, because VELOCITY_BURST is the one pattern that raises several
 * alerts per injection. A weight that drifts back to uniform would reintroduce
 * that with no test failing and no log line to notice.
 */
@DisplayName("fraud pattern injection weights")
class FraudPatternWeightTest {

    @Test
    @DisplayName("every pattern can be injected — a weight of zero would hide a rule")
    void everyPatternIsReachable() {
        assertThat(FraudPattern.values())
                .allSatisfy(p -> assertThat(p.injectionWeight())
                        .as("%s must be reachable by the mixer", p)
                        .isPositive());
    }

    @Test
    @DisplayName("the multi-alert pattern is injected less often than the single-alert ones")
    void velocityBurstIsWeightedDown() {
        // VELOCITY_BURST publishes six to nine transactions on one card and
        // everything past the fifth alerts, so one injection is two to four
        // alerts where every other pattern is exactly one.
        int burst = FraudPattern.VELOCITY_BURST.injectionWeight();

        assertThat(FraudPattern.values())
                .filteredOn(p -> p != FraudPattern.VELOCITY_BURST)
                .allSatisfy(p -> assertThat(p.injectionWeight())
                        .as("%s should be injected more often than a burst", p)
                        .isGreaterThan(burst));
    }

    @Test
    @DisplayName("no pattern dominates the mix")
    void noPatternTakesMoreThanAThird() {
        int total = 0;
        for (FraudPattern p : FraudPattern.values()) {
            total += p.injectionWeight();
        }
        for (FraudPattern p : FraudPattern.values()) {
            assertThat((double) p.injectionWeight() / total)
                    .as("share of injections that are %s", p)
                    .isLessThan(0.34);
        }
    }
}
