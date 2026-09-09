package com.overwatch.engine.rule.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Rand formatting for the reason strings analysts read.
 *
 * <p>"R52 340.00" is the South African convention — space as the thousands
 * separator. Reasons are read by people, so they are formatted for people.
 */
final class Money {

    private Money() {
    }

    static String format(BigDecimal amount) {
        if (amount == null) {
            return "R0.00";
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator('.');
        DecimalFormat fmt = new DecimalFormat("#,##0.00", symbols);
        return "R" + fmt.format(amount.setScale(2, RoundingMode.HALF_UP));
    }
}
