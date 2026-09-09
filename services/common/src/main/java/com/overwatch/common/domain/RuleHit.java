package com.overwatch.common.domain;

import java.util.Map;

/**
 * The result of one rule matching one transaction.
 *
 * <p>Rules return a hit rather than a boolean because a single boolean throws away
 * everything useful: how much this rule should move the risk score, and why it fired.
 * The {@code reason} is what an analyst reads in the dashboard, so it is written for
 * a human ("6 transactions in 8 minutes, threshold is 5 in 10").
 *
 * @param ruleType  identifies the rule implementation, e.g. "VELOCITY"
 * @param ruleId    the configured rule row that produced this hit
 * @param weight    contribution to the accumulated risk score, 0.0–1.0
 * @param reason    human-readable explanation shown to analysts
 * @param evidence  the values the rule actually looked at, for auditability
 * @param shadow    true if the rule was in shadow mode, so this raises no alert
 */
public record RuleHit(
        String ruleType,
        Long ruleId,
        double weight,
        String reason,
        Map<String, Object> evidence,
        boolean shadow
) {
}
