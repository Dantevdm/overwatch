package com.overwatch.engine.rule.impl;

import com.overwatch.engine.rule.FraudRule;
import com.overwatch.engine.rule.RuleContext;
import com.overwatch.engine.rule.RuleFinding;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Flags a card used more often than expected inside a short window.
 *
 * <p>The classic signature of a card that has been stolen or skimmed: whoever has
 * it tests a small amount, then drains the balance quickly before the card can be
 * blocked. Genuine cardholders occasionally trip this — a supermarket run split
 * across tills — which is why it accompanies rather than carries an alert.
 */
@Component
public class VelocityRule implements FraudRule {

    static final String TYPE = "VELOCITY";
    private static final int DEFAULT_MAX_COUNT = 5;
    private static final int DEFAULT_WINDOW_MINUTES = 10;

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public Optional<RuleFinding> evaluate(RuleContext ctx) {
        int maxCount = ctx.parameters().getInt("maxCount", DEFAULT_MAX_COUNT);
        int windowMinutes = ctx.parameters().getInt("windowMinutes", DEFAULT_WINDOW_MINUTES);

        // A misconfigured rule should be inert, not throw on the consumer thread.
        if (maxCount <= 0 || windowMinutes <= 0) {
            return Optional.empty();
        }

        Duration window = Duration.ofMinutes(windowMinutes);
        long count = ctx.history().countRecent(ctx.transaction().cardId(), window);

        if (count <= maxCount) {
            return Optional.empty();
        }

        return Optional.of(RuleFinding.of(
                "%d transactions on this card in %d minutes, limit is %d"
                        .formatted(count, windowMinutes, maxCount),
                Map.of("count", count,
                       "maxCount", maxCount,
                       "windowMinutes", windowMinutes)));
    }
}
