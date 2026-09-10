package com.overwatch.simulator.generate;

/**
 * A fraud shape the simulator can deliberately produce.
 *
 * <p>Each corresponds to a rule, so the pipeline can be demonstrated end to end:
 * inject the pattern, watch the rule fire, see the alert appear. Without this the
 * stack produces only clean traffic and there is nothing to look at.
 */
public enum FraudPattern {

    /** A single very large amount. Trips HIGH_VALUE. */
    HIGH_VALUE(3),

    /** A burst of transactions on one card in minutes. Trips VELOCITY. */
    VELOCITY_BURST(1),

    /** Timestamped in the small hours, SAST. Trips LATE_NIGHT. */
    LATE_NIGHT(3),

    /** An exact multiple of R1 000 above the floor. Trips ROUND_AMOUNT. */
    ROUND_AMOUNT(3),

    /** Acquired outside South Africa. Trips CROSS_BORDER. */
    CROSS_BORDER(3),

    /** Crypto, gambling or forex merchant. Trips CATEGORY_WATCHLIST. */
    HIGH_RISK_CATEGORY(3),

    /**
     * Several signals at once — the shape that actually reaches CRITICAL, and the
     * one worth showing an interviewer, because it demonstrates that scoring
     * accumulates rather than latching on the first hit.
     */
    COMPOUND(3);

    /**
     * How often the random mixer should pick this pattern, relative to the others.
     *
     * <p>Not uniform, because the patterns do not cost the same. Every one of
     * these produces a single transaction and therefore a single alert — except
     * VELOCITY_BURST, which produces six to nine transactions on one card, of
     * which everything past the fifth raises its own alert, and which then leaves
     * that card over the threshold for the rest of the ten-minute window so
     * ordinary traffic on it alerts too. One injection, two to four alerts.
     *
     * <p>Picked uniformly, it therefore drowned out the other six rules in the
     * alert list while looking perfectly fair in the injection count. Weighting
     * it at a third balances the thing a reader actually sees.
     *
     * <p>Only the mixer consults this. Pressing a pattern's button on the
     * simulator screen injects exactly that pattern, which is the entire point of
     * the button.
     */
    private final int injectionWeight;

    FraudPattern(int injectionWeight) {
        this.injectionWeight = injectionWeight;
    }

    public int injectionWeight() {
        return injectionWeight;
    }
}
