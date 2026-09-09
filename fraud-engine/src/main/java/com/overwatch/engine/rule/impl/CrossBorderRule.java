package com.overwatch.engine.rule.impl;

import com.overwatch.engine.rule.FraudRule;
import com.overwatch.engine.rule.RuleContext;
import com.overwatch.engine.rule.RuleFinding;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Flags transactions acquired outside the card's home country.
 *
 * <p>Entirely ordinary for anyone travelling, which is exactly why this
 * accompanies an alert rather than causing one. It becomes interesting in
 * combination — a foreign acquirer at 02:00 for a round amount is a different
 * proposition from a foreign acquirer alone.
 */
@Component
public class CrossBorderRule implements FraudRule {

    static final String TYPE = "CROSS_BORDER";
    private static final String DEFAULT_HOME = "ZA";

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public Optional<RuleFinding> evaluate(RuleContext ctx) {
        String country = ctx.transaction().countryCode();
        if (country == null || country.isBlank()) {
            return Optional.empty();
        }

        String home = ctx.parameters()
                .getString("homeCountry", DEFAULT_HOME)
                .toLowerCase(Locale.ROOT);
        Set<String> allowed = ctx.parameters().getLowerCaseSet("allowedCountries", Set.of(home));
        String actual = country.trim().toLowerCase(Locale.ROOT);

        if (allowed.contains(actual)) {
            return Optional.empty();
        }

        return Optional.of(RuleFinding.of(
                "Acquired in %s, outside the card's home country (%s)".formatted(
                        country.toUpperCase(Locale.ROOT), home.toUpperCase(Locale.ROOT)),
                Map.of("countryCode", country.toUpperCase(Locale.ROOT),
                       "homeCountry", home.toUpperCase(Locale.ROOT))));
    }
}
