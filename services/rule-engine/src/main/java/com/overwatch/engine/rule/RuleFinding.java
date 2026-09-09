package com.overwatch.engine.rule;

import java.util.Map;

/**
 * What a rule returns when it fires.
 *
 * <p>Deliberately carries no weight and no rule id. A rule decides <em>whether</em>
 * a transaction is suspicious and <em>why</em>; how much that counts toward the risk
 * score, and whether it alerts at all, is configuration the engine applies
 * afterwards. Keeping those apart is what lets the same rule run at one weight in
 * production and another in shadow without touching its code.
 *
 * @param reason   human-readable, written for the analyst who reads it in the
 *                 dashboard — "6 transactions in 8 minutes, limit is 5 in 10"
 * @param evidence the values the rule actually looked at, so a decision can be
 *                 audited later without re-running anything
 */
public record RuleFinding(String reason, Map<String, Object> evidence) {

    public static RuleFinding of(String reason, Map<String, Object> evidence) {
        return new RuleFinding(reason, Map.copyOf(evidence));
    }
}
