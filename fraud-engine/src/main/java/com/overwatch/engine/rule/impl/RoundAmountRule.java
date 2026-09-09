package com.overwatch.engine.rule.impl;

import com.overwatch.engine.rule.FraudRule;
import com.overwatch.engine.rule.RuleContext;
import com.overwatch.engine.rule.RuleFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Flags large, suspiciously round amounts.
 *
 * <p>Genuine retail spending rarely lands exactly on R10 000 — real baskets carry
 * odd cents and VAT. Amounts chosen by a person moving money deliberately very
 * often are round. The floor matters: without it this fires on every R1 000 ATM
 * withdrawal in the country.
 */
@Component
public class RoundAmountRule implements FraudRule {

    static final String TYPE = "ROUND_AMOUNT";
    private static final BigDecimal DEFAULT_FLOOR = new BigDecimal("5000");
    private static final BigDecimal DEFAULT_MULTIPLE = new BigDecimal("1000");

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public Optional<RuleFinding> evaluate(RuleContext ctx) {
        BigDecimal amount = ctx.transaction().amount();
        if (amount == null) {
            return Optional.empty();
        }

        BigDecimal floor = ctx.parameters().getDecimal("floor", DEFAULT_FLOOR);
        BigDecimal multiple = ctx.parameters().getDecimal("multipleOf", DEFAULT_MULTIPLE);

        if (multiple.signum() <= 0 || amount.compareTo(floor) < 0) {
            return Optional.empty();
        }
        if (amount.remainder(multiple).signum() != 0) {
            return Optional.empty();
        }

        return Optional.of(RuleFinding.of(
                "%s is an exact multiple of %s".formatted(
                        Money.format(amount), Money.format(multiple)),
                Map.of("amount", amount,
                       "multipleOf", multiple,
                       "floor", floor)));
    }
}
