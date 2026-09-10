package com.overwatch.engine.pipeline;

import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Evaluation;
import com.overwatch.common.domain.RuleHit;
import com.overwatch.common.domain.Transaction;
import com.overwatch.common.persistence.FraudAlertEntity;
import com.overwatch.common.persistence.ShadowRuleHitEntity;
import com.overwatch.common.persistence.TransactionEntity;
import com.overwatch.engine.config.RuleConfigProvider;
import com.overwatch.engine.persistence.repository.FraudAlertRepository;
import com.overwatch.engine.persistence.repository.ShadowRuleHitRepository;
import com.overwatch.engine.persistence.repository.TransactionRepository;
import com.overwatch.engine.rule.RuleEngine;
import com.overwatch.engine.rule.TransactionHistory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pipeline's own behaviour, with the broker and the database mocked out.
 *
 * <p>{@link TransactionConsumer} is deliberately thin so that this class holds
 * everything worth asserting — and for a long time nothing asserted it. These
 * are the decisions that live here rather than in the rule engine: that a
 * shadow hit is recorded without raising an alert, that a failed publish costs
 * a notification and not a detection, that the transaction is stored before it
 * is evaluated so it counts toward its own velocity window, and that a
 * redelivered record is not processed twice.
 *
 * <p>The meter registry is real rather than mocked. Asserting that
 * {@code counter(...)} was called proves the code calls Micrometer; asserting
 * on the registry proves the counter that a Grafana panel reads actually moved,
 * with the tags that panel groups by.
 */
class TransactionProcessorTest {

    private TransactionRepository transactions;
    private FraudAlertRepository alerts;
    private ShadowRuleHitRepository shadowHits;
    private RuleEngine engine;
    private KafkaTemplate<String, Object> kafka;
    private MeterRegistry meters;
    private TransactionProcessor processor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        transactions = mock(TransactionRepository.class);
        alerts = mock(FraudAlertRepository.class);
        shadowHits = mock(ShadowRuleHitRepository.class);
        engine = mock(RuleEngine.class);
        kafka = mock(KafkaTemplate.class);
        meters = new SimpleMeterRegistry();

        RuleConfigProvider config = mock(RuleConfigProvider.class);
        when(config.active()).thenReturn(List.of());

        // Succeeds by default; the failure path overrides this.
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        processor = new TransactionProcessor(transactions, alerts, shadowHits, engine,
                config, mock(TransactionHistory.class), kafka, meters);
    }

    // ---- fixtures ---------------------------------------------------------

    private static Transaction txn() {
        return txn(Instant.now());
    }

    private static Transaction txn(Instant when) {
        return new Transaction(UUID.randomUUID(), "CARD-1", "cust-1", "Thabo Nkosi",
                new BigDecimal("65000.00"), "ZAR",
                "FX Trading ZA", "forex", "CN", Channel.ONLINE, when, Map.of());
    }

    private static RuleHit scoring(String type, long id, double weight) {
        return new RuleHit(type, id, weight, "because", Map.of("k", "v"), false);
    }

    private static RuleHit shadow(String type, long id, double weight) {
        return new RuleHit(type, id, weight, "because", Map.of("k", "v"), true);
    }

    /** Wires the engine to return exactly these hits for any transaction. */
    private void engineReturns(Transaction t, RuleHit... hits) {
        when(engine.evaluate(any(), any(), any())).thenReturn(Evaluation.of(t, List.of(hits)));
    }

    private double counter(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }

    // ---- alerting ---------------------------------------------------------

    @Test
    @DisplayName("an alert carries every rule that contributed to it, not just the strongest")
    void alertCarriesEveryContributingRule() {
        Transaction t = txn();
        engineReturns(t, scoring("HIGH_VALUE", 1, 0.40),
                         scoring("CROSS_BORDER", 3, 0.30),
                         scoring("ROUND_AMOUNT", 6, 0.15));

        Optional<Evaluation> result = processor.process(t);

        assertThat(result).isPresent();
        ArgumentCaptor<FraudAlertEntity> saved = ArgumentCaptor.forClass(FraudAlertEntity.class);
        verify(alerts).save(saved.capture());

        assertThat(saved.getValue().getTransactionId()).isEqualTo(t.id());
        assertThat(saved.getValue().getSeverity()).isEqualTo("CRITICAL");   // 0.85
        assertThat(saved.getValue().getHits())
                .extracting(h -> h.getRuleType())
                .containsExactlyInAnyOrder("HIGH_VALUE", "CROSS_BORDER", "ROUND_AMOUNT");
    }

    @Test
    @DisplayName("a score below the alert threshold raises nothing and publishes nothing")
    void belowThresholdRaisesNothing() {
        Transaction t = txn();
        engineReturns(t, scoring("ROUND_AMOUNT", 6, 0.15));   // 0.15 < 0.30

        assertThat(processor.process(t)).isPresent();

        verify(alerts, never()).save(any());
        verify(kafka, never()).send(anyString(), anyString(), any());
        // Still counted as processed, and its score still recorded — a quiet
        // transaction is data, not an absence of data.
        assertThat(counter("transactions.processed")).isEqualTo(1.0);
        assertThat(meters.find("fraud.risk.score").summary().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the transaction is stored before it is evaluated, so it counts toward its own velocity")
    void storedBeforeEvaluated() {
        Transaction t = txn();
        engineReturns(t, scoring("HIGH_VALUE", 1, 0.40));

        processor.process(t);

        InOrder order = inOrder(transactions, engine);
        order.verify(transactions).save(any(TransactionEntity.class));
        order.verify(engine).evaluate(any(), any(), any());
    }

    // ---- shadow mode ------------------------------------------------------

    @Test
    @DisplayName("a shadow hit is recorded but raises no alert, however heavily it is weighted")
    void shadowHitRaisesNoAlert() {
        Transaction t = txn();
        engineReturns(t, shadow("AMOUNT_DEVIATION", 7, 0.90));

        assertThat(processor.process(t)).isPresent();

        verify(shadowHits).save(any(ShadowRuleHitEntity.class));
        verify(alerts, never()).save(any());
        verify(kafka, never()).send(anyString(), anyString(), any());
        assertThat(counter("fraud.shadow.hits", "rule", "AMOUNT_DEVIATION")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a shadow hit does not contribute to the score or appear on the alert")
    void shadowHitStaysOffTheAlert() {
        Transaction t = txn();
        engineReturns(t, scoring("HIGH_VALUE", 1, 0.40),
                         shadow("AMOUNT_DEVIATION", 7, 0.90));

        processor.process(t);

        ArgumentCaptor<FraudAlertEntity> saved = ArgumentCaptor.forClass(FraudAlertEntity.class);
        verify(alerts).save(saved.capture());
        // 0.40 alone, not 1.30 capped to 1.0 — the shadow weight must not leak in.
        assertThat(saved.getValue().getRiskScore()).isEqualByComparingTo("0.40");
        assertThat(saved.getValue().getHits()).hasSize(1);
        assertThat(saved.getValue().getHits().get(0).getRuleType()).isEqualTo("HIGH_VALUE");
    }

    @Test
    @DisplayName("an alert is stamped with the transaction's own time, not the time it was processed")
    void alertCarriesTheTransactionTime() {
        // A backdated transaction is the whole point: replaying a backlog, or
        // seeding a history, hands the engine events from weeks ago. If the alert
        // only remembered when it was written, every one of them would claim to
        // have happened at the moment the engine caught up, and the dashboard's
        // time axis would show one spike instead of the history.
        Instant lastWeek = Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS);
        Transaction t = txn(lastWeek);
        engineReturns(t, scoring("HIGH_VALUE", 1, 0.40));

        processor.process(t);

        ArgumentCaptor<FraudAlertEntity> saved = ArgumentCaptor.forClass(FraudAlertEntity.class);
        verify(alerts).save(saved.capture());
        assertThat(saved.getValue().getOccurredAt()).isEqualTo(lastWeek);
    }

    // ---- redelivery -------------------------------------------------------

    @Test
    @DisplayName("a redelivered transaction is skipped rather than alerted on twice")
    void redeliveryIsSkipped() {
        Transaction t = txn();
        when(transactions.existsById(t.id())).thenReturn(true);

        Optional<Evaluation> result = processor.process(t);

        assertThat(result).isEmpty();
        verify(transactions, never()).save(any());
        verify(engine, never()).evaluate(any(), any(), any());
        verify(alerts, never()).save(any());
        verify(shadowHits, never()).save(any());
        verify(kafka, never()).send(anyString(), anyString(), any());
        assertThat(counter("transactions.redelivered")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a redelivery does not double-count the metrics an analyst reads")
    void redeliveryDoesNotDoubleCount() {
        Transaction t = txn();
        engineReturns(t, scoring("HIGH_VALUE", 1, 0.40));

        processor.process(t);                                    // first delivery
        when(transactions.existsById(t.id())).thenReturn(true);  // now stored
        processor.process(t);                                    // redelivery

        assertThat(counter("transactions.processed")).isEqualTo(1.0);
        assertThat(counter("fraud.alerts", "severity", "MEDIUM")).isEqualTo(1.0);
        assertThat(counter("fraud.alerts.by.rule", "rule", "HIGH_VALUE")).isEqualTo(1.0);
        assertThat(meters.find("fraud.risk.score").summary().count()).isEqualTo(1);
        verify(alerts).save(any());   // exactly once
    }

    @Test
    @DisplayName("the redelivery counter exists at zero, so a quiet pipeline is not 'No data'")
    void redeliveryCounterIsRegisteredAtZero() {
        assertThat(meters.find("transactions.redelivered").counter()).isNotNull();
        assertThat(counter("transactions.redelivered")).isZero();
    }

    // ---- publishing -------------------------------------------------------

    @Test
    @DisplayName("a failed publish is counted and does not lose the detection")
    void failedPublishKeepsTheDetection() {
        Transaction t = txn();
        engineReturns(t, scoring("HIGH_VALUE", 1, 0.40));
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        // Must not propagate: the alert is already durable in Postgres.
        assertThat(processor.process(t)).isPresent();

        verify(alerts).save(any());
        assertThat(counter("fraud.alerts.publish.failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the alert is keyed by transaction id, so one card movement stays on one partition")
    void publishIsKeyedByTransaction() {
        Transaction t = txn();
        engineReturns(t, scoring("HIGH_VALUE", 1, 0.40));

        processor.process(t);

        verify(kafka).send(eq("fraud-alerts"), eq(t.id().toString()), any());
    }

    // ---- metrics ----------------------------------------------------------

    @Test
    @DisplayName("throughput carries the category and channel the dashboards group by")
    void throughputCarriesItsDimensions() {
        Transaction t = txn();
        engineReturns(t);   // no hits at all

        processor.process(t);

        assertThat(counter("transactions.processed", "category", "forex", "channel", "ONLINE"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("flagged value is recorded only for transactions that actually alerted")
    void flaggedValueOnlyForAlerts() {
        Transaction quiet = txn();
        engineReturns(quiet, scoring("ROUND_AMOUNT", 6, 0.15));
        processor.process(quiet);
        assertThat(meters.find("fraud.amount.flagged.zar").summary().count()).isZero();

        Transaction flagged = txn();
        engineReturns(flagged, scoring("HIGH_VALUE", 1, 0.40));
        processor.process(flagged);

        var summary = meters.find("fraud.amount.flagged.zar").summary();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(65_000.0);
    }

    @Test
    @DisplayName("detection latency is timed for every transaction")
    void latencyIsTimed() {
        Transaction t = txn();
        engineReturns(t);

        processor.process(t);

        assertThat(meters.find("fraud.detection.latency").timer().count()).isEqualTo(1);
    }

    // ---- defensive mapping ------------------------------------------------

    @Test
    @DisplayName("a transaction arriving without an id is given one rather than failing the insert")
    void missingIdIsGenerated() {
        Transaction t = new Transaction(null, "CARD-1", "cust-1", "Thabo Nkosi",
                new BigDecimal("100.00"), "ZAR",
                "Checkers", "groceries", "ZA", Channel.POS, Instant.now(), Map.of());
        engineReturns(t);

        processor.process(t);

        ArgumentCaptor<TransactionEntity> saved = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactions).save(saved.capture());
        assertThat(saved.getValue().getId()).isNotNull();
        // With no id there is nothing to deduplicate on, so it must not be
        // treated as a redelivery.
        verify(transactions, never()).existsById(any());
    }

    @Test
    @DisplayName("a transaction arriving without a currency is stored as ZAR")
    void missingCurrencyDefaultsToZar() {
        Transaction t = new Transaction(UUID.randomUUID(), "CARD-1", "cust-1", "Thabo Nkosi",
                new BigDecimal("100.00"), null,
                "Checkers", "groceries", "ZA", Channel.POS, Instant.now(), Map.of());
        engineReturns(t);

        processor.process(t);

        ArgumentCaptor<TransactionEntity> saved = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactions).save(saved.capture());
        assertThat(saved.getValue().getCurrency()).isEqualTo("ZAR");
    }
}
