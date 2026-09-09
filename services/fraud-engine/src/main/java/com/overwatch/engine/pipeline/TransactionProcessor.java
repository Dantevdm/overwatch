package com.overwatch.engine.pipeline;

import com.overwatch.common.Topics;
import com.overwatch.common.domain.*;
import com.overwatch.engine.config.RuleConfigProvider;
import com.overwatch.common.persistence.*;
import com.overwatch.engine.persistence.repository.*;
import com.overwatch.engine.rule.RuleEngine;
import com.overwatch.engine.rule.TransactionHistory;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * The pipeline: persist, evaluate, record, publish.
 *
 * <p>Order matters. The transaction is saved <em>before</em> evaluation so that it
 * counts toward its own velocity check — an analyst reading "6 transactions in 8
 * minutes" expects that number to describe what actually happened, this one
 * included.
 *
 * <p>The whole thing runs in one transaction. An alert without its rule hits is
 * worse than no alert, because it tells an analyst something is wrong without
 * saying what.
 */
@Service
public class TransactionProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);

    private final TransactionRepository transactions;
    private final FraudAlertRepository alerts;
    private final ShadowRuleHitRepository shadowHits;
    private final RuleEngine engine;
    private final RuleConfigProvider config;
    private final TransactionHistory history;
    private final KafkaTemplate<String, Object> kafka;
    private final MeterRegistry meters;
    private final Timer evaluationTimer;
    private final DistributionSummary riskScore;
    private final DistributionSummary amountFlagged;

    public TransactionProcessor(TransactionRepository transactions,
                                FraudAlertRepository alerts,
                                ShadowRuleHitRepository shadowHits,
                                RuleEngine engine,
                                RuleConfigProvider config,
                                TransactionHistory history,
                                KafkaTemplate<String, Object> kafka,
                                MeterRegistry meters) {
        this.transactions = transactions;
        this.alerts = alerts;
        this.shadowHits = shadowHits;
        this.engine = engine;
        this.config = config;
        this.history = history;
        this.kafka = kafka;
        this.meters = meters;
        this.evaluationTimer = Timer.builder("fraud.detection.latency")
                .description("Time to evaluate one transaction against the rule set")
                // A histogram, not client-side percentiles. publishPercentiles()
                // computes p50/p95/p99 inside this JVM and exports them as
                // gauges, which cannot be aggregated: averaging two instances'
                // p95 is not the p95, and there is no way to re-quantile them or
                // ask a different percentile later. publishPercentileHistogram()
                // exports the bucket counts instead, so Prometheus can do
                // histogram_quantile() over any set of instances and any
                // percentile — which is exactly what the Grafana panels do
                // (`sum by (le) (rate(..._bucket[5m]))`). With the gauges those
                // panels had no _bucket series to read and showed "No data".
                .publishPercentileHistogram()
                // Bound the buckets. Left open, Micrometer spreads ~70 of them
                // across nanoseconds to minutes; evaluation is sub-millisecond
                // to single-digit milliseconds, so most would be empty and the
                // resolution would sit in the wrong place.
                .minimumExpectedValue(Duration.ofNanos(100_000))   // 100µs
                .maximumExpectedValue(Duration.ofSeconds(1))
                .register(meters);

        // Both of these were plain summaries, which export only _count, _sum and
        // a rolling _max. That is enough for a mean and nothing else, so the
        // "Risk score distribution" panel was reduced to plotting
        // _sum/_count — a single average line under a title promising a
        // distribution. A mean is the one statistic that cannot answer the
        // question either panel exists to ask: whether scores pile up against
        // the threshold, and whether the flagged amounts are a few large ones or
        // many small ones.
        //
        // Explicit bucket edges rather than publishPercentileHistogram(). The
        // automatic buckets are generated for timers and spread on a fixed
        // power-of-ten ladder; these two quantities have known, meaningful
        // edges, and choosing them means the heatmap rows line up with the
        // numbers people actually reason about instead of 1.7ms-style
        // boundaries. Both still export _bucket series, so histogram_quantile()
        // works over them the same way.
        this.riskScore = DistributionSummary.builder("fraud.risk.score")
                .description("Composite risk score assigned to each transaction")
                // 0 to 1 by construction, in tenths, with 0.5 and 0.75 added
                // because those are where the severity bands actually break.
                .serviceLevelObjectives(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.75, 0.8, 0.9, 1.0)
                .register(meters);

        this.amountFlagged = DistributionSummary.builder("fraud.amount.flagged.zar")
                .description("Value of each flagged transaction, in ZAR")
                .baseUnit("zar")
                // Bracketing the generator: ordinary merchant ranges start
                // around R29 and the widest tops out at R120 000, and the
                // injected high-value pattern reaches R115 000. Edges are round
                // ZAR figures so the axis reads as money.
                .serviceLevelObjectives(100, 500, 1_000, 2_500, 5_000,
                        10_000, 25_000, 50_000, 100_000, 200_000)
                .register(meters);

        // At zero from startup, for the same reason as the consumer's failure
        // counters: "no alert publish has ever failed" and "this panel is
        // broken" must not look the same on a dashboard.
        meters.counter("fraud.alerts.publish.failed");
    }

    @Transactional
    public Evaluation process(Transaction txn) {
        transactions.save(toEntity(txn));

        Evaluation evaluation = evaluationTimer.record(
                () -> engine.evaluate(txn, config.active(), history));

        recordShadowHits(txn, evaluation);

        if (evaluation.isAlert()) {
            FraudAlert alert = persistAlert(txn, evaluation);
            publish(alert);
        }

        recordMetrics(txn, evaluation);
        return evaluation;
    }

    private void recordShadowHits(Transaction txn, Evaluation evaluation) {
        for (RuleHit hit : evaluation.shadowHits()) {
            shadowHits.save(new ShadowRuleHitEntity(
                    txn.id(), hit.ruleId(), hit.ruleType(),
                    BigDecimal.valueOf(hit.weight()), hit.reason(), hit.evidence()));
            meters.counter("fraud.shadow.hits", "rule", hit.ruleType()).increment();
        }
    }

    private FraudAlert persistAlert(Transaction txn, Evaluation evaluation) {
        UUID alertId = UUID.randomUUID();
        FraudAlertEntity entity = new FraudAlertEntity(
                alertId, txn.id(),
                BigDecimal.valueOf(evaluation.riskScore()),
                evaluation.severity().name(),
                FraudAlert.STATUS_OPEN,
                txn.amount(), txn.currency());

        for (RuleHit hit : evaluation.scoringHits()) {
            entity.addHit(new AlertRuleHitEntity(
                    hit.ruleId(), hit.ruleType(),
                    BigDecimal.valueOf(hit.weight()), hit.reason(), hit.evidence()));
        }
        alerts.save(entity);

        log.info("Alert {} raised for transaction {} — score {} ({}), {} rules fired",
                alertId, txn.id(), evaluation.riskScore(), evaluation.severity(),
                evaluation.scoringHits().size());

        return new FraudAlert(alertId, txn.id(), evaluation.riskScore(),
                evaluation.severity(), evaluation.scoringHits(), FraudAlert.STATUS_OPEN,
                txn.amount(), txn.currency(), java.time.Instant.now());
    }

    /**
     * Publish for downstream consumers. Notification, case management and
     * anything else can subscribe without the engine knowing they exist.
     */
    private void publish(FraudAlert alert) {
        kafka.send(Topics.FRAUD_ALERTS, alert.transactionId().toString(), alert)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // The alert is already durable in Postgres; a failed
                        // publish loses a notification, not the detection.
                        log.error("Failed to publish alert {}", alert.id(), ex);
                        meters.counter("fraud.alerts.publish.failed").increment();
                    }
                });
    }

    private void recordMetrics(Transaction txn, Evaluation evaluation) {
        meters.counter("transactions.processed",
                "category", txn.merchantCategory(),
                "channel", String.valueOf(txn.channel())).increment();

        riskScore.record(evaluation.riskScore());

        if (evaluation.isAlert()) {
            meters.counter("fraud.alerts", "severity", evaluation.severity().name()).increment();
            amountFlagged.record(txn.amount().doubleValue());
            for (RuleHit hit : evaluation.scoringHits()) {
                meters.counter("fraud.alerts.by.rule", "rule", hit.ruleType()).increment();
            }
        }
    }

    private static TransactionEntity toEntity(Transaction t) {
        return new TransactionEntity(
                t.id() == null ? UUID.randomUUID() : t.id(),
                t.cardId(), t.amount(),
                t.currency() == null ? Transaction.DEFAULT_CURRENCY : t.currency(),
                t.merchantName(), t.merchantCategory(), t.countryCode(),
                String.valueOf(t.channel()), t.timestamp(), t.metadata());
    }
}
