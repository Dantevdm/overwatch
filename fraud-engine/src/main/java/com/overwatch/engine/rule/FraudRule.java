package com.overwatch.engine.rule;

import java.util.Optional;

/**
 * One fraud detection strategy.
 *
 * <p>Adding a detection technique means adding one class that implements this
 * interface and a row in {@code fraud_rules} naming its type. Spring collects
 * every implementation into the engine automatically — there is no registry to
 * update and no switch statement to extend.
 *
 * <p>Implementations must be stateless and side-effect free. They are called once
 * per transaction on the consumer thread, and anything they hold on to becomes a
 * correctness problem the moment the engine is scaled to more than one instance.
 */
public interface FraudRule {

    /**
     * Matches the {@code rule_type} column. This is the join between a row of
     * configuration and the code that implements it.
     */
    String ruleType();

    /**
     * Evaluate the transaction. Empty means the rule did not fire — the common
     * case by a wide margin, since the overwhelming majority of card traffic is
     * legitimate.
     */
    Optional<RuleFinding> evaluate(RuleContext context);
}
