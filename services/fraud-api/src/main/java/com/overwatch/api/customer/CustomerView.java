package com.overwatch.api.customer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shapes the cardholder screens read.
 *
 * <p>Grouped in one file because they are one contract, and meaningless apart
 * from the profile they describe.
 */
public final class CustomerView {

    private CustomerView() {
    }

    /**
     * A cardholder in a search result.
     *
     * @param id           tokenised cardholder reference
     * @param name         display name as the stream reported it
     * @param transactions how many we have observed
     * @param totalSpend   sum of those amounts
     * @param lastSeen     most recent transaction
     * @param cards        distinct cards seen for this person
     * @param alerts       how many alerts their transactions have raised
     */
    public record Summary(
            String id,
            String name,
            long transactions,
            BigDecimal totalSpend,
            Instant lastSeen,
            int cards,
            long alerts
    ) {
    }

    /** One slice of a breakdown — a category, a country, a channel. */
    public record Slice(String label, long count, BigDecimal value, double share) {
    }

    /**
     * One line of the risk summary, with what it is and what it contributed.
     *
     * <p>Every signal carries its own evidence. A profile that shows a number and
     * a colour is asking to be trusted; one that shows which behaviours produced
     * the number can be argued with, which is the only useful kind — an
     * investigator has to be able to say "that is not fraud, they live in London
     * half the year" and see exactly which line to discount.
     *
     * @param label        the behaviour, in words
     * @param detail       the measurement behind it
     * @param points       what it added to the summary score
     * @param elevated     whether this is above what the population does
     */
    public record RiskSignal(String label, String detail, int points, boolean elevated) {
    }

    /**
     * A cardholder's risk summary.
     *
     * <p>Explicitly a heuristic over observed behaviour, not a model: the score is
     * the sum of the signals below it and nothing more. It is presented as
     * evidence rather than as a verdict because that is all it can honestly
     * support — a learned score would need labelled outcomes, and nothing in this
     * system has confirmed a single alert as fraud.
     *
     * @param band            NORMAL, WATCH or ELEVATED
     * @param score           sum of the signal points, 0–100
     * @param signals         what produced it
     * @param alerts          alerts raised against this cardholder
     * @param alertsBySeverity those alerts by severity band
     * @param caveat          what the score does not mean
     */
    public record RiskProfile(
            String band,
            int score,
            List<RiskSignal> signals,
            long alerts,
            Map<String, Long> alertsBySeverity,
            String caveat
    ) {
    }

    /** One alert on the profile timeline. */
    public record AlertLine(
            UUID id,
            UUID transactionId,
            double riskScore,
            String severity,
            String status,
            BigDecimal amount,
            /** When the flagged transaction happened, so it lines up with the trail. */
            Instant occurredAt,
            List<String> rules
    ) {
    }

    /** One transaction on the profile timeline. */
    public record TransactionLine(
            UUID id,
            String cardId,
            BigDecimal amount,
            String merchantName,
            String merchantCategory,
            String countryCode,
            String channel,
            Instant occurredAt,
            boolean flagged
    ) {
    }

    /**
     * Everything known about one cardholder.
     *
     * @param id             tokenised cardholder reference
     * @param name           display name
     * @param homeCity       where their ordinary spend happens
     * @param bank           issuing bank
     * @param cards          the cards seen for them
     * @param transactions   how many transactions this profile is built from
     * @param historyCapped  whether the aggregates were computed over a capped
     *                       window rather than the whole history — a profile that
     *                       silently summarises a sample is worse than one that
     *                       says it did
     * @param totalSpend     sum of observed amounts
     * @param averageAmount  mean transaction value
     * @param largestAmount  largest single transaction
     * @param firstSeen      earliest observed transaction
     * @param lastSeen       most recent
     * @param byCategory     spend by merchant category
     * @param byCountry      spend by acquiring country
     * @param byChannel      spend by how the card was presented
     * @param byHour         transactions by hour of day, SAST, 24 entries
     * @param risk           the risk summary
     * @param alerts         alerts raised against them
     * @param recent         their most recent transactions
     */
    public record Profile(
            String id,
            String name,
            String homeCity,
            String bank,
            List<String> cards,
            long transactions,
            boolean historyCapped,
            BigDecimal totalSpend,
            BigDecimal averageAmount,
            BigDecimal largestAmount,
            Instant firstSeen,
            Instant lastSeen,
            List<Slice> byCategory,
            List<Slice> byCountry,
            List<Slice> byChannel,
            List<Long> byHour,
            RiskProfile risk,
            List<AlertLine> alerts,
            List<TransactionLine> recent
    ) {
    }
}
