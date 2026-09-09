package com.overwatch.engine.rule.impl;

import com.overwatch.engine.rule.FraudRule;
import com.overwatch.engine.rule.RuleContext;
import com.overwatch.engine.rule.RuleFinding;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Flags transactions in the small hours, local time.
 *
 * <p>Transactions are stored in UTC; South Africa is UTC+2 with no daylight
 * saving. Evaluating this in UTC would shift the window by two hours and quietly
 * flag the wrong transactions, so the timestamp is converted to the configured
 * zone first. That conversion is the entire substance of this rule.
 *
 * <p>Weak on its own — plenty of legitimate online spending happens at 02:00 —
 * which is why it carries a low weight and earns its place mainly by corroborating
 * something else.
 *
 * <p>Both bounds are inclusive whole hours: {@code startHour 1, endHour 4} matches
 * 01:00 through 04:59. A window may wrap past midnight.
 */
@Component
public class LateNightRule implements FraudRule {

    static final String TYPE = "LATE_NIGHT";
    private static final int DEFAULT_START_HOUR = 1;
    private static final int DEFAULT_END_HOUR = 4;
    private static final String DEFAULT_ZONE = "Africa/Johannesburg";

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public Optional<RuleFinding> evaluate(RuleContext ctx) {
        if (ctx.transaction().timestamp() == null) {
            return Optional.empty();
        }

        int startHour = ctx.parameters().getInt("startHour", DEFAULT_START_HOUR);
        int endHour = ctx.parameters().getInt("endHour", DEFAULT_END_HOUR);
        if (startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23) {
            return Optional.empty();
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(ctx.parameters().getString("timezone", DEFAULT_ZONE));
        } catch (DateTimeException e) {
            zone = ZoneId.of(DEFAULT_ZONE);
        }

        ZonedDateTime local = ctx.transaction().timestamp().atZone(zone);
        int hour = local.getHour();

        if (!withinWindow(hour, startHour, endHour)) {
            return Optional.empty();
        }

        // endHour is inclusive of the whole hour: endHour 4 covers up to 04:59.
        // The reason string says so explicitly — an analyst reading "inside the
        // 01:00-04:00 window" about an 04:30 transaction would rightly distrust it.
        return Optional.of(RuleFinding.of(
                "Transaction at %02d:%02d %s, inside the %02d:00-%02d:59 window"
                        .formatted(hour, local.getMinute(), local.getZone().getId(),
                                   startHour, endHour),
                Map.of("localHour", hour,
                       "timezone", zone.getId(),
                       "startHour", startHour,
                       "endHour", endHour)));
    }

    /**
     * Inclusive of both ends, and handles a window that wraps past midnight —
     * 22:00 to 03:00 is a perfectly reasonable configuration and must not silently
     * match nothing.
     */
    private static boolean withinWindow(int hour, int start, int end) {
        return start <= end
                ? hour >= start && hour <= end
                : hour >= start || hour <= end;
    }
}
