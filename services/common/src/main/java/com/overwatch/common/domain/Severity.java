package com.overwatch.common.domain;

/**
 * Alert severity, derived from the accumulated risk score rather than set by hand.
 * Keeping the thresholds here means the engine and the API cannot disagree about
 * what "HIGH" means.
 */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static Severity fromScore(double score) {
        if (score >= 0.80) return CRITICAL;
        if (score >= 0.55) return HIGH;
        if (score >= 0.30) return MEDIUM;
        return LOW;
    }
}
