package com.overwatch.simulator.data;

import java.math.BigDecimal;

/**
 * A South African merchant, with the spend range that is normal for it.
 *
 * <p>The range matters more than the name. Generic sample data makes it impossible
 * to tell whether a rule is sensible — a R60 000 transaction is only obviously
 * suspicious if the surrounding traffic looks like real grocery and fuel spend.
 *
 * @param name     as it would appear on a statement
 * @param category normalised category the rules match against
 * @param minor    bottom of the usual range, ZAR
 * @param major    top of the usual range, ZAR
 */
public record Merchant(String name, String category, BigDecimal minor, BigDecimal major) {

    public static Merchant of(String name, String category, int minor, int major) {
        return new Merchant(name, category, BigDecimal.valueOf(minor), BigDecimal.valueOf(major));
    }
}
