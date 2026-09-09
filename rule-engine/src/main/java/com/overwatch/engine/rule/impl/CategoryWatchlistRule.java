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
 * Flags merchant categories over-represented in confirmed fraud.
 *
 * <p>Crypto exchanges, gambling and forex share the property that funds move
 * quickly and are effectively unrecoverable once gone, which makes them where
 * stolen card details get cashed out. The watchlist is configuration rather than
 * code, because which categories matter changes with the fraud landscape and
 * should not need a release.
 */
@Component
public class CategoryWatchlistRule implements FraudRule {

    static final String TYPE = "CATEGORY_WATCHLIST";
    private static final Set<String> DEFAULT_CATEGORIES = Set.of("crypto", "gambling", "forex");

    @Override
    public String ruleType() {
        return TYPE;
    }

    @Override
    public Optional<RuleFinding> evaluate(RuleContext ctx) {
        String category = ctx.transaction().merchantCategory();
        if (category == null || category.isBlank()) {
            return Optional.empty();
        }

        Set<String> watchlist = ctx.parameters().getLowerCaseSet("categories", DEFAULT_CATEGORIES);
        String actual = category.trim().toLowerCase(Locale.ROOT);

        if (!watchlist.contains(actual)) {
            return Optional.empty();
        }

        return Optional.of(RuleFinding.of(
                "Merchant category '%s' is on the high-risk watchlist".formatted(category),
                Map.of("merchantCategory", category,
                       "merchantName", String.valueOf(ctx.transaction().merchantName()))));
    }
}
