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
 * <p>The cardholder fields are nullable. A card is an instrument and a person may
 * carry several, so the cardholder is what ties a pattern spread across two cards
 * back to one subject — but this system observes a payment stream rather than
 * owning customer master data, and an authorisation that arrives without a
 * cardholder reference is a transaction we still have to evaluate, not one we can
 * reject. Null means "the stream did not say", which is a different thing from a
 * placeholder that looks like an answer.
 *
 * @param id               server-assigned identity
 * @param cardId           tokenised card reference, never a real PAN
 * @param customerId       tokenised cardholder reference, never a national ID
 * @param customerName     cardholder display name as presented on the authorisation
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
        String customerId,
        String customerName,
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
