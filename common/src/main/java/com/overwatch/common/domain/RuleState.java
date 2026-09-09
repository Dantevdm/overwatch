package com.overwatch.common.domain;

/**
 * Lifecycle state of a configured rule.
 *
 * <p>{@link #SHADOW} is the reason this is an enum rather than a boolean. A shadow
 * rule is evaluated against every transaction and its hits are recorded, but it
 * raises no alerts. That is how a new rule gets tuned in production: run it silently
 * against live traffic, review what it would have caught, then promote it to
 * {@link #ENABLED} once its false-positive rate is acceptable.
 */
public enum RuleState {

    /** Evaluated; hits raise real alerts. */
    ENABLED,

    /** Not evaluated at all. */
    DISABLED,

    /** Evaluated; hits are recorded for analysis but raise no alerts. */
    SHADOW;

    /** Whether the engine should run this rule. */
    public boolean isEvaluated() {
        return this == ENABLED || this == SHADOW;
    }

    /** Whether hits from this rule should surface as real alerts. */
    public boolean raisesAlerts() {
        return this == ENABLED;
    }
}
