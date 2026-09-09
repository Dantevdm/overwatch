package com.overwatch.engine.rule.impl;

import com.overwatch.engine.rule.FraudRule;
import com.overwatch.engine.rule.RuleContext;
import com.overwatch.engine.rule.RuleFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Flags transactions above a rand threshold.
 *
 * <p>The strongest single signal available, and also the one most likely to catch
 * something entirely legitimate — a deposit, a car payment, a flight. That tension
 * is why it carries a heavy weight but not one that alerts on its own at CRITICAL.
 */
@Component
public class HighValueRule implements FraudRule {

    static final String TYPE = "HIGH_VALUE";
    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("50000");

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public Optional<RuleFinding> evaluate(RuleContext ctx) {
        BigDecimal threshold = ctx.parameters().getDecimal("threshold", DEFAULT_THRESHOLD);
        BigDecimal amount = ctx.transaction().amount();

        if (amount == null || amount.compareTo(threshold) <= 0) {
            return Optional.empty();
        }

        return Optional.of(RuleFinding.of(
                "Amount %s exceeds the %s threshold".formatted(
                        Money.format(amount), Money.format(threshold)),
                Map.of("amount", amount,
                       "threshold", threshold,
                       "exceededBy", amount.subtract(threshold))));
    }
}
