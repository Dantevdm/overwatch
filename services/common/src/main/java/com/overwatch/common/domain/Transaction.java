package com.overwatch.common.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A categorized card transaction — the unit of work flowing through the pipeline.
 *
 * <p>This is the wire contract between the simulator and the engine. It is a record
 * because it is immutable data with no behaviour; the engine reads it and never
 * mutates it.
 *
 * <p>Amounts are {@link BigDecimal}. Money is never a double.
 *
 * @param id               server-assigned identity
 * @param cardId           tokenised card reference, never a real PAN
 * @param amount           transaction value, in {@code currency}
 * @param currency         ISO-4217 code; ZAR throughout this system
 * @param merchantName     display name, e.g. "Checkers Brackenfell"
 * @param merchantCategory normalised category, e.g. "groceries", "crypto"
 * @param countryCode      ISO-3166 alpha-2 of the acquiring country
 * @param channel          how the card was presented
 * @param timestamp        when the transaction occurred (UTC; rules convert to SAST)
 * @param metadata         free-form context — issuing bank, city, device fingerprint
 */
public record Transaction(
        UUID id,
        String cardId,
        BigDecimal amount,
        String currency,
        String merchantName,
        String merchantCategory,
        String countryCode,
        Channel channel,
        Instant timestamp,
        Map<String, String> metadata
) {
    public static final String DEFAULT_CURRENCY = "ZAR";
}
