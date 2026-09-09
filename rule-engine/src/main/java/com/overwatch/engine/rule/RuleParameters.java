package com.overwatch.engine.rule;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Typed, defaulted access to a rule's JSONB parameters.
 *
 * <p>Backed by a plain map rather than a Jackson node so rules stay free of any
 * serialisation library. Every accessor takes a default, because a rule row whose
 * parameters are missing a key must degrade to sensible behaviour rather than
 * throw — a malformed rule should not take the pipeline down.
 */
public final class RuleParameters {

    private final Map<String, Object> values;

    private RuleParameters(Map<String, Object> values) {
        this.values = values;
    }

    public static RuleParameters of(Map<String, Object> values) {
        return new RuleParameters(values == null ? Map.of() : Map.copyOf(values));
    }

    public static RuleParameters empty() {
        return new RuleParameters(Map.of());
    }

    public int getInt(String key, int fallback) {
        Object v = values.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public BigDecimal getDecimal(String key, BigDecimal fallback) {
        Object v = values.get(key);
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public String getString(String key, String fallback) {
        Object v = values.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /**
     * A set of lower-cased strings. Comparisons against merchant categories and
     * country codes should not fail because of casing that came out of a
     * configuration form.
     */
    public Set<String> getLowerCaseSet(String key, Set<String> fallback) {
        Object v = values.get(key);
        if (!(v instanceof Collection<?> raw) || raw.isEmpty()) {
            return fallback;
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : raw) {
            if (o != null) {
                out.add(o.toString().trim().toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? fallback : Set.copyOf(out);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
