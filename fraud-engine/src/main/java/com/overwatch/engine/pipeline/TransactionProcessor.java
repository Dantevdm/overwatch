package com.overwatch.engine.pipeline;

import com.overwatch.common.Topics;
import com.overwatch.common.domain.*;
import com.overwatch.engine.config.RuleConfigProvider;
import com.overwatch.engine.persistence.entity.*;
import com.overwatch.engine.persistence.repository.*;
import com.overwatch.engine.rule.RuleEngine;
import com.overwatch.engine.rule.TransactionHistory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
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
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
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

        meters.summary("fraud.risk.score").record(evaluation.riskScore());

        if (evaluation.isAlert()) {
            meters.counter("fraud.alerts", "severity", evaluation.severity().name()).increment();
            meters.summary("fraud.amount.flagged.zar").record(txn.amount().doubleValue());
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
