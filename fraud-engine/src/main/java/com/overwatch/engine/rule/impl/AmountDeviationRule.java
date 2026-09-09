package com.overwatch.engine.rule.impl;

import com.overwatch.engine.rule.FraudRule;
import com.overwatch.engine.rule.RuleContext;
import com.overwatch.engine.rule.RuleFinding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Flags a transaction far above what this card normally spends.
 *
 * <p>Ships in SHADOW state. The idea is sound — a card that has never exceeded
 * R800 suddenly spending R9 000 is genuinely interesting — but it depends on a
 * baseline, and a baseline is only trustworthy with enough history behind it. Run
 * it silently, look at what it would have caught, then promote it. That workflow
 * is the reason shadow mode exists.
 */
@Component
public class AmountDeviationRule implements FraudRule {

    static final String TYPE = "AMOUNT_DEVIATION";
    private static final int DEFAULT_MULTIPLE = 5;
    private static final int DEFAULT_MINIMUM_HISTORY = 10;

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

        int multiple = ctx.parameters().getInt("multipleOfAverage", DEFAULT_MULTIPLE);
        int minimumHistory = ctx.parameters().getInt("minimumHistory", DEFAULT_MINIMUM_HISTORY);
        if (multiple <= 0) {
            return Optional.empty();
        }

        // No baseline, no opinion. Firing on a card with three transactions behind
        // it would flag every new customer's first real purchase.
        Optional<BigDecimal> baseline =
                ctx.history().averageAmount(ctx.transaction().cardId(), minimumHistory);
        if (baseline.isEmpty() || baseline.get().signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal average = baseline.get();
        BigDecimal threshold = average.multiply(BigDecimal.valueOf(multiple));
        if (amount.compareTo(threshold) <= 0) {
            return Optional.empty();
        }

        BigDecimal ratio = amount.divide(average, 1, RoundingMode.HALF_UP);
        return Optional.of(RuleFinding.of(
                "%s is %sx this card's average of %s".formatted(
                        Money.format(amount), ratio.toPlainString(), Money.format(average)),
                Map.of("amount", amount,
                       "average", average,
                       "ratio", ratio,
                       "multipleOfAverage", multiple)));
    }
}
