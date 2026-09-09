package com.overwatch.common.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An alert raised because a transaction accumulated enough risk to warrant review.
 *
 * <p>An alert carries every rule that contributed, not just the highest-scoring one.
 * An analyst deciding whether this is genuine fraud needs the full picture: a
 * transaction that tripped three weak rules is a different story from one that
 * tripped a single strong one, even at the same total score.
 *
 * @param id            alert identity
 * @param transactionId the transaction under suspicion
 * @param riskScore     sum of contributing rule weights, capped at 1.0
 * @param severity      derived from {@code riskScore} via {@link Severity#fromScore}
 * @param hits          every rule that fired, in evaluation order
 * @param status        analyst disposition: OPEN, REVIEWING, CONFIRMED, CLEARED
 * @param amount        denormalised from the transaction so alert lists need no join
 * @param currency      denormalised alongside {@code amount}
 * @param createdAt     when the engine raised this
 */
public record FraudAlert(
        UUID id,
        UUID transactionId,
        double riskScore,
        Severity severity,
        List<RuleHit> hits,
        String status,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CLEARED = "CLEARED";
}
