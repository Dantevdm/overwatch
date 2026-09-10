package com.overwatch.common.domain;

/**
 * Alert severity, derived from the accumulated risk score rather than set by hand.
 * Keeping the thresholds here means the engine and the API cannot disagree about
 * what "HIGH" means.
 *
 * <p>The bands start where alerting starts. {@link Evaluation#ALERT_THRESHOLD} is
 * 0.30, so nothing below 0.30 is ever persisted as an alert — and for a long time
 * MEDIUM also began at 0.30, which made LOW arithmetically unreachable: every
 * alert in the store was MEDIUM or above, the severity filter had a value that
 * matched nothing, and the severity mix on the dashboard was missing its whole
 * bottom band. The fix is a relabelling, not a change in what alerts: the entry
 * band is now LOW, and the boundaries above it sit where the rule weights
 * actually accumulate.
 *
 * <p>With the seeded weights a score is a sum of the rules that fired, so the
 * bands are chosen against those sums rather than as round numbers: one scoring
 * rule lands in LOW, two in MEDIUM, three in HIGH, and only a genuine pile-up
 * reaches CRITICAL. Change a rule's weight and this is the file to re-read.
 */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /** Lower edge of each band, inclusive. A score on a boundary is the higher band. */
    private static final double CRITICAL_FROM = 0.80;
    private static final double HIGH_FROM = 0.60;
    private static final double MEDIUM_FROM = 0.45;

    public static Severity fromScore(double score) {
        if (score >= CRITICAL_FROM) return CRITICAL;
        if (score >= HIGH_FROM) return HIGH;
        if (score >= MEDIUM_FROM) return MEDIUM;
        return LOW;
    }
}
