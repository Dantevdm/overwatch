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
    HIGH_VALUE,

    /** A burst of transactions on one card in minutes. Trips VELOCITY. */
    VELOCITY_BURST,

    /** Timestamped in the small hours, SAST. Trips LATE_NIGHT. */
    LATE_NIGHT,

    /** An exact multiple of R1 000 above the floor. Trips ROUND_AMOUNT. */
    ROUND_AMOUNT,

    /** Acquired outside South Africa. Trips CROSS_BORDER. */
    CROSS_BORDER,

    /** Crypto, gambling or forex merchant. Trips CATEGORY_WATCHLIST. */
    HIGH_RISK_CATEGORY,

    /**
     * Several signals at once — the shape that actually reaches CRITICAL, and the
     * one worth showing an interviewer, because it demonstrates that scoring
     * accumulates rather than latching on the first hit.
     */
    COMPOUND
}
