package com.overwatch.api.dto;

import com.overwatch.common.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire shapes for the API.
 *
 * <p>Deliberately separate from the JPA entities. Serialising entities directly
 * drags lazy associations into the response, leaks the schema into the contract,
 * and turns any column rename into a breaking API change.
 */
public final class Dtos {

    private Dtos() {
    }

    /**
     * A transaction as the API returns it.
     *
     * <p>The cardholder fields are nullable, and that is the contract rather than
     * an oversight: rows written before the cardholder existed have none, and an
     * authorisation can arrive without one. Null means the stream did not say.
     */
    public record TransactionView(
            UUID id, String cardId, String customerId, String customerName,
            BigDecimal amount, String currency,
            String merchantName, String merchantCategory, String countryCode,
            String channel, Instant occurredAt, Map<String, String> metadata) {

        public static TransactionView from(TransactionEntity e) {
            return new TransactionView(e.getId(), e.getCardId(),
                    e.getCustomerId(), e.getCustomerName(),
                    e.getAmount(), e.getCurrency(),
                    e.getMerchantName(), e.getMerchantCategory(), e.getCountryCode(),
                    e.getChannel(), e.getOccurredAt(), e.getMetadata());
        }
    }

    public record RuleHitView(String ruleType, Long ruleId, BigDecimal weight,
                              String reason, Map<String, Object> evidence) {

        public static RuleHitView from(AlertRuleHitEntity e) {
            return new RuleHitView(e.getRuleType(), e.getRuleId(), e.getWeight(),
                    e.getReason(), e.getEvidence());
        }
    }

    /**
     * Both timestamps are exposed, and they mean different things.
     * {@code occurredAt} is when the transaction happened — the one a reader
     * wants on a timeline or an axis. {@code createdAt} is when the engine wrote
     * the alert, which is only interesting as the other end of the detection lag,
     * and after a replay the two are hours or weeks apart.
     */
    public record AlertView(
            UUID id, UUID transactionId, BigDecimal riskScore, String severity,
            String status, BigDecimal amount, String currency,
            Instant occurredAt, Instant createdAt, Instant resolvedAt,
            List<RuleHitView> hits) {

        /** Summary form — no hits, for list views where they would be noise. */
        public static AlertView summary(FraudAlertEntity e) {
            return new AlertView(e.getId(), e.getTransactionId(), e.getRiskScore(),
                    e.getSeverity(), e.getStatus(), e.getAmount(), e.getCurrency(),
                    e.getOccurredAt(), e.getCreatedAt(), e.getResolvedAt(), List.of());
        }

        public static AlertView detailed(FraudAlertEntity e) {
            return new AlertView(e.getId(), e.getTransactionId(), e.getRiskScore(),
                    e.getSeverity(), e.getStatus(), e.getAmount(), e.getCurrency(),
                    e.getOccurredAt(), e.getCreatedAt(), e.getResolvedAt(),
                    e.getHits().stream().map(RuleHitView::from).toList());
        }
    }

    public record RuleView(Long id, String ruleType, String name, String description,
                           String state, BigDecimal weight, Map<String, Object> parameters,
                           Instant updatedAt) {

        public static RuleView from(FraudRuleEntity e) {
            return new RuleView(e.getId(), e.getRuleType(), e.getName(), e.getDescription(),
                    e.getState(), e.getWeight(), e.getParameters(), e.getUpdatedAt());
        }
    }

    /**
     * Operational metrics per rule.
     *
     * <p>{@code falsePositiveRate} is null until analysts have dispositioned
     * alerts. Reporting 0% when nothing has been reviewed would be worse than
     * reporting nothing — it reads as a perfect rule.
     */
    public record RulePerformance(
            Long ruleId, String ruleType, String name, String state,
            BigDecimal weight, long timesFired, double shareOfAlerts,
            long confirmed, long cleared, Double falsePositiveRate,
            long shadowHits) {
    }

    /**
     * @param rangeMinutes  the window the two time series cover, echoed back so the
     *                      dashboard renders what the server actually applied
     *                      rather than what it asked for after clamping
     * @param bucketSeconds width of one bucket in those series, chosen from the
     *                      range. The client needs it to label an axis honestly:
     *                      the same chart is "alerts per 10 seconds" over five
     *                      minutes and "alerts per 6 hours" over a week.
     */
    public record DashboardStats(
            long totalTransactions, long transactionsLastHour,
            long totalAlerts, long openAlerts,
            double averageRiskScore, BigDecimal flaggedLast24hZar,
            Map<String, Long> alertsBySeverity,
            Map<String, Long> transactionsByCategory,
            int rangeMinutes, long bucketSeconds,
            List<TimeBucket> alertsOverTime,
            /**
             * Transaction volume over the same buckets. Two alerts an hour is a
             * quiet night or a broken detector depending entirely on how many
             * transactions went past, and the dashboard could not tell the
             * difference without this.
             */
            List<TimeBucket> transactionsOverTime,
            List<SeverityBucket> severityOverTime) {
    }

    /**
     * One bucket of alerts. Named {@code bucket} rather than {@code hour} because
     * it is only an hour at one of the seven selectable ranges.
     */
    public record TimeBucket(Instant bucket, long count) {
    }

    /**
     * One bucket of alerts, split by severity.
     *
     * <p>{@code counts} always carries every severity, zeros included. A chart
     * whose series appear and disappear as buckets go quiet draws lines that jump
     * between non-adjacent points, which reads as a spike that never happened.
     */
    public record SeverityBucket(Instant bucket, Map<String, Long> counts) {
    }

    /** What-if request: a candidate configuration and a window to replay. */
    public record ReplayRequest(
            String ruleType, BigDecimal weight, Map<String, Object> parameters, int hours) {
    }

    /**
     * What the candidate rule would have produced. Nothing is written — this is
     * the point: you get to measure a threshold change before making it.
     */
    public record ReplayResult(
            String ruleType, Map<String, Object> parameters, int hoursReplayed,
            long transactionsEvaluated, long wouldHaveFired, double firePercentage,
            List<ReplaySample> samples) {
    }

    public record ReplaySample(UUID transactionId, String merchantName,
                               BigDecimal amount, String reason) {
    }

    /**
     * A threshold sweep: one rule, one parameter, many candidate values.
     *
     * <p>Replay answers "what would this threshold have caught". That is the
     * wrong shape of answer for the question actually being asked, which is
     * "where should this threshold sit" -- and getting there by replaying one
     * value at a time means one full pass over the history per value, and a
     * person holding six numbers in their head to compare them. A sweep is the
     * curve.
     *
     * @param values candidate values for {@code parameter}. Numbers for a numeric
     *               parameter; the sweep does not interpret them beyond handing
     *               each one to the rule.
     */
    public record SweepRequest(String ruleType, String parameter, List<Object> values,
                               Map<String, Object> baseParameters, int hours) {
    }

    public record SweepResult(
            String ruleType, String parameter, int hoursReplayed,
            long transactionsEvaluated,
            /** True when the window held more transactions than replay will read. */
            boolean capped,
            List<SweepPoint> points) {
    }

    /**
     * One candidate value and what it would have caught.
     *
     * <p>{@code firePercentage} rather than only a count, because the count is
     * meaningless without knowing how many transactions were evaluated -- and the
     * whole reason to sweep is to compare points against each other.
     */
    public record SweepPoint(Object value, long wouldHaveFired, double firePercentage) {
    }

    /**
     * What a rule can usefully be swept on, and a sensible ladder to start from.
     *
     * <p>Served to the UI so the candidate values are not hardcoded in
     * JavaScript. A rule with no sweepable parameter says so and says why --
     * CROSS_BORDER has a country list rather than a number, and VELOCITY cannot
     * be replayed at all because replay has no card history to count against.
     */
    public record Sweepable(String ruleType, String parameter, List<Object> suggested,
                            String unit, String reason) {
    }
}
