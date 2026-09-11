package com.overwatch.engine;

import com.overwatch.common.Topics;
import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Evaluation;
import com.overwatch.common.domain.Severity;
import com.overwatch.common.domain.Transaction;
import com.overwatch.engine.persistence.repository.FraudAlertRepository;
import com.overwatch.engine.persistence.repository.OutboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The pipeline, end to end, through the infrastructure it actually runs on.
 *
 * <p>Every other test in this module mocks the broker and the database, and that
 * is the right shape for asserting logic. It also means every one of them would
 * still pass if the alert serialised to something no consumer can read, if a
 * migration never applied, or if the outbox were written and never drained —
 * because none of those failures live in the code being mocked. This test exists
 * for that class of fault, which is the class that reaches production.
 *
 * <p>What it asserts, in order: a transaction published to {@code transactions}
 * is consumed, evaluated, persisted as an alert, queued in the outbox inside the
 * same transaction, drained by the poller, and lands on {@code fraud-alerts} as a
 * document a consumer can read.
 *
 * <p>Redpanda rather than Kafka, and the same image the compose stack runs, so
 * this exercises what is deployed rather than a close relative of it.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        // The rule that fires, made certain. R95 000 is over the seeded
        // HIGH_VALUE threshold, but a test that depends on the seeded
        // configuration is a test that breaks when someone retunes a rule — so
        // the outbox is polled hard and the assertion is about the plumbing.
        "overwatch.outbox.poll-millis=100",
        "overwatch.engine.rule-refresh-seconds=1",
})
@DisplayName("The pipeline, through a real broker and a real database")
class PipelineIT {

    /**
     * Pinned by digest-free tag to match docker-compose.yml. A floating
     * {@code latest} here would mean this test and the deployed stack could
     * differ, which defeats the reason for using Redpanda rather than Kafka.
     */
    @Container
    @ServiceConnection
    static final RedpandaContainer REDPANDA =
            new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v24.2.7"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private FraudAlertRepository alerts;

    @Autowired
    private OutboxRepository outbox;

    @Test
    @DisplayName("a high-value transaction becomes an alert on the topic")
    void transactionBecomesAnAlertOnTheTopic() {
        try (KafkaConsumer<String, String> downstream = subscribed()) {
            Transaction txn = highValue();

            // Published as raw JSON by a plain client rather than through an
            // application bean, so what goes onto the topic is what the
            // simulator puts there — a document with no type headers. Going
            // through the engine's own template would test the engine against
            // its own serialiser, which is the one thing an integration test
            // must not do.
            publish(txn);

            // 1. It reached the database. This is the detection; everything
            //    after it is announcement.
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(alerts.count()).isEqualTo(1));

            // 2. It reached the topic, as something readable. Asserting on the
            //    parsed document rather than on the string is the point: the
            //    failure this catches is a payload that is published but is not
            //    the alert — a JSON string containing JSON, say, which is
            //    exactly what the default serialiser would have produced.
            JsonNode alert = awaitOneAlert(downstream);
            assertThat(alert.get("transactionId").asText()).isEqualTo(txn.id().toString());
            // A band that exists and a score over the threshold, not a specific
            // band. Which band this lands in is a property of the seeded rule
            // weights -- it is MEDIUM today because HIGH_VALUE and ROUND_AMOUNT
            // both fire on R95 000 -- and pinning it here would make retuning a
            // rule break an infrastructure test. What this test is for is
            // whether the document is readable and correct in shape.
            assertThat(Severity.valueOf(alert.get("severity").asText()))
                    .isIn((Object[]) Severity.values());
            assertThat(alert.get("riskScore").asDouble())
                    .isGreaterThanOrEqualTo(Evaluation.ALERT_THRESHOLD);
            assertThat(alert.get("id").asText()).isNotBlank();

            // 3. The outbox drained rather than merely grew. A row left pending
            //    would mean the alert was published by something other than the
            //    poller — or not at all, with this test passing on a message
            //    from a previous run.
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() ->
                            assertThat(outbox.countByPublishedAtIsNull()).isZero());
        }
    }

    private static Transaction highValue() {
        return new Transaction(
                UUID.randomUUID(), "CARD-IT-1", "cust-it-1", "Integration Test",
                new BigDecimal("95000.00"), "ZAR",
                "Test Merchant", "electronics", "ZA", Channel.ONLINE,
                Instant.now(), Map.of());
    }

    private void publish(Transaction txn) {
        Properties props = new Properties();
        props.put("bootstrap.servers", REDPANDA.getBootstrapServers());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(Topics.TRANSACTIONS, txn.cardId(),
                    new ObjectMapper().writeValueAsString(txn)));
            producer.flush();
        }
    }

    /** A consumer on fraud-alerts, subscribed before anything is sent. */
    private KafkaConsumer<String, String> subscribed() {
        Properties props = new Properties();
        props.put("bootstrap.servers", REDPANDA.getBootstrapServers());
        props.put("group.id", "pipeline-it-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(Topics.FRAUD_ALERTS));
        return consumer;
    }

    private static JsonNode awaitOneAlert(KafkaConsumer<String, String> consumer) {
        ObjectMapper json = new ObjectMapper();
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                return json.readTree(record.value());
            }
        }
        throw new AssertionError("No alert arrived on " + Topics.FRAUD_ALERTS + " within 30s");
    }
}
