package com.overwatch.simulator.data;

/**
 * How a cardholder spends.
 *
 * <p>Without this every generated person looks the same, and a 360 view of a
 * customer is then a view of the population mean rendered six hundred times. The
 * point of a profile is that it distinguishes somebody, which requires that
 * somebody be distinguishable in the first place.
 *
 * <p>These are also what make the false-positive conversation available. A
 * {@link #TRAVELLER} trips the cross-border rule regularly and is not a
 * fraudster; a {@link #HIGH_SPENDER} trips the high-value rule the same way. Any
 * fraud system worth discussing has to separate "unusual for the population" from
 * "unusual for this person", and a generator that produces only the population
 * mean cannot pose that question.
 *
 * @param label      how the archetype reads on a profile screen
 * @param share      relative frequency in the population
 * @param amountSkew exponent on the uniform draw within a merchant's range —
 *                   below 1 pushes spend toward the top of the range, above 1
 *                   toward the bottom; 2.0 is the ordinary case
 * @param foreignRate fraction of this holder's spend acquired outside the country
 * @param nightRate   fraction of this holder's spend in the small hours
 * @param onlineBias  how strongly this holder prefers card-not-present channels
 */
public enum Archetype {

    /** The bulk of any real card base: local, modest, mostly in person. */
    EVERYDAY("Everyday spender", 58, 2.0, 0.004, 0.02, 0.0),

    /** Larger baskets, more often at the top of a merchant's range. */
    HIGH_SPENDER("High spender", 12, 0.8, 0.02, 0.03, 0.2),

    /** Lives on their phone: subscriptions, delivery, airtime. */
    ONLINE_HEAVY("Online-first", 16, 1.6, 0.03, 0.10, 0.75),

    /**
     * Genuinely abroad often. The most useful person in the book, because they
     * are the honest cross-border alert that a real analyst has to dismiss.
     */
    TRAVELLER("Frequent traveller", 8, 1.2, 0.22, 0.06, 0.35),

    /** Shift worker or night owl: real spend at hours the rules find suspicious. */
    NIGHT_OWL("Late-hours spender", 6, 1.8, 0.01, 0.34, 0.3);

    private final String label;
    private final int share;
    private final double amountSkew;
    private final double foreignRate;
    private final double nightRate;
    private final double onlineBias;

    Archetype(String label, int share, double amountSkew,
              double foreignRate, double nightRate, double onlineBias) {
        this.label = label;
        this.share = share;
        this.amountSkew = amountSkew;
        this.foreignRate = foreignRate;
        this.nightRate = nightRate;
        this.onlineBias = onlineBias;
    }

    public String label() { return label; }
    public int share() { return share; }
    public double amountSkew() { return amountSkew; }
    public double foreignRate() { return foreignRate; }
    public double nightRate() { return nightRate; }
    public double onlineBias() { return onlineBias; }
}
