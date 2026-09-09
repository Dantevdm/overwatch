package com.overwatch.engine.config;

import com.overwatch.common.domain.RuleState;
import com.overwatch.engine.rule.RuleParameters;

/**
 * One row of {@code fraud_rules}, resolved into the form the engine works with.
 *
 * @param id         row identity, recorded on every hit so a hit can be traced
 *                   back to the exact configuration that produced it
 * @param ruleType   selects the {@link com.overwatch.engine.rule.FraudRule}
 * @param name       display name for the dashboard
 * @param state      ENABLED, SHADOW or DISABLED
 * @param weight     contribution to the risk score when this rule fires
 * @param parameters rule-specific configuration
 */
public record RuleConfig(
        Long id,
        String ruleType,
        String name,
        RuleState state,
        double weight,
        RuleParameters parameters
) {
    public boolean isEvaluated() {
        return state != null && state.isEvaluated();
    }

    public boolean raisesAlerts() {
        return state != null && state.raisesAlerts();
    }
}
