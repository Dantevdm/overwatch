package com.overwatch.engine;

import com.overwatch.common.Topics;
import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Evaluation;
import com.overwatch.common.domain.Severity;
import com.overwatch.common.domain.Transaction;
import com.overwatch.engine.persistence.repository.FraudAlertRepository;
import com.overwatch.engine.persistence.repository.OutboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

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
            //
            //    Asserted as "at least one alert exists", not "exactly one".
            //    Both tests in this class share a container and a database, and
            //    a count pinned to 1 makes each of them depend on the other not
            //    having run — which JUnit does not promise and which fails in a
            //    way that reads as a broken pipeline.
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(alerts.count()).isPositive());

            // 2. It reached the topic, as something readable. Asserting on the
            //    parsed document rather than on the string is the point: the
            //    failure this catches is a payload that is published but is not
            //    the alert — a JSON string containing JSON, say, which is
            //    exactly what the default serialiser would have produced.
            // Matched on the transaction id rather than taking whatever
            // arrives first, for the same reason: the other test also produces
            // an alert, and a consumer reading from the beginning of the topic
            // would otherwise assert against it.
            JsonNode alert = awaitAlertFor(downstream, txn.id());
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

    @Test
    @DisplayName("bytes that are not JSON reach the dead-letter topic unchanged")
    void malformedBytesAreDeadLetteredVerbatim() {
        try (KafkaConsumer<String, String> deadLetters = subscribedTo(Topics.TRANSACTIONS_DLT)) {
            String garbage = "this is not JSON at all";
            publishRaw(garbage);

            // Byte for byte. This is the whole reason the topic is worth having:
            // what lands there has to be the message that failed, not this
            // service's rendering of a message it could not read, or it cannot
            // be replayed or handed to whoever sent it. The bytes survive
            // because the recoverer digs them out of the deserializer's own
            // exception header rather than out of the record, whose value is
            // null by the time anything sees it.
            // Found by matching its content rather than by taking whatever is
            // first on the topic. Both tests in this class dead-letter a record
            // onto the same topic and both consumers read it from the beginning,
            // so "the first record" is whichever test ran first.
            ConsumerRecord<String, String> dead =
                    awaitMatching(deadLetters, garbage::equals);

            // The failure travels with it. A dead letter with no cause attached
            // is a message in a folder nobody can act on.
            assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(Topics.TRANSACTIONS);
            assertThat(header(dead, "kafka_dlt-exception-fqcn")).isNotBlank();
            assertThat(header(dead, "kafka_dlt-exception-message")).isNotBlank();
        }
    }

    @Test
    @DisplayName("a record that parses but cannot be processed is dead-lettered, and the partition keeps moving")
    void unprocessableRecordIsDeadLetteredAndDoesNotStallThePartition() {
        try (KafkaConsumer<String, String> deadLetters = subscribedTo(Topics.TRANSACTIONS_DLT)) {
            // Valid JSON of the wrong shape. Worth testing separately from the
            // malformed case because it takes an entirely different path: this
            // one *deserialises* — the fields simply are not there, and Jackson
            // is not configured to object — so the failure happens downstream in
            // processing, and the record on the dead-letter topic is the parsed
            // transaction rather than the bytes.
            publishRaw("{\"this\":\"is not a transaction\"}");

            ConsumerRecord<String, String> dead =
                    awaitMatching(deadLetters, value -> value.contains("\"id\":null"));
            assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(Topics.TRANSACTIONS);
            assertThat(header(dead, "kafka_dlt-exception-fqcn")).isNotBlank();

            // Stated as what it is rather than as byte fidelity, because it is
            // not: the unknown field the producer sent is gone, and every field
            // of the contract it never sent is present and null. What survives
            // is the contract, which is what a replay would act on.
            assertThat(dead.value()).contains("\"amount\":null");

            // And the partition kept moving. A good transaction published after
            // the bad one still becomes an alert — the property the old
            // catch-and-drop had, which must not have been lost in gaining the
            // dead-letter topic. Measured as an increase rather than a total, so
            // it does not depend on which test ran first.
            long before = alerts.count();
            publish(highValue());
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(alerts.count()).isGreaterThan(before));
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Transaction highValue() {
        return new Transaction(
                UUID.randomUUID(), "CARD-IT-1", "cust-it-1", "Integration Test",
                new BigDecimal("95000.00"), "ZAR",
                "Test Merchant", "electronics", "ZA", Channel.ONLINE,
                Instant.now(), Map.of());
    }

    private void publish(Transaction txn) {
        publishRaw(new ObjectMapper().writeValueAsString(txn));
    }

    /** Puts exactly these bytes on the transactions topic. */
    private void publishRaw(String payload) {
        Properties props = new Properties();
        props.put("bootstrap.servers", REDPANDA.getBootstrapServers());
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(Topics.TRANSACTIONS, "CARD-IT-1", payload));
            producer.flush();
        }
    }

    /** A consumer on fraud-alerts, subscribed before anything is sent. */
    private KafkaConsumer<String, String> subscribed() {
        return subscribedTo(Topics.FRAUD_ALERTS);
    }

    /** A consumer on one topic, subscribed before anything is sent to it. */
    private KafkaConsumer<String, String> subscribedTo(String topic) {
        Properties props = new Properties();
        props.put("bootstrap.servers", REDPANDA.getBootstrapServers());
        props.put("group.id", "pipeline-it-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /** The alert for one transaction, ignoring any others on the topic. */
    private static JsonNode awaitAlertFor(KafkaConsumer<String, String> consumer, UUID transactionId) {
        ObjectMapper json = new ObjectMapper();
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                JsonNode alert = json.readTree(record.value());
                if (transactionId.toString().equals(alert.path("transactionId").asText())) {
                    return alert;
                }
            }
        }
        throw new AssertionError("No alert for " + transactionId + " arrived on "
                + Topics.FRAUD_ALERTS + " within 30s");
    }

    /** The first record on the subscribed topic whose value satisfies the predicate. */
    private static ConsumerRecord<String, String> awaitMatching(
            KafkaConsumer<String, String> consumer, Predicate<String> matching) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (matching.test(record.value())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No matching record arrived within 30s");
    }
}
