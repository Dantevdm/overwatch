package com.overwatch.engine.config;

import com.overwatch.common.Topics;
import com.overwatch.common.domain.Transaction;
import com.overwatch.engine.pipeline.UnreadableRecordException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Where a transaction goes when the engine cannot process it.
 *
 * <p>Until this existed, a record the engine could not handle was logged,
 * counted and dropped. That kept the partition moving, which is the important
 * half, but it meant the only trace of a lost transaction was a log line and a
 * number going up — and neither of those can be replayed, inspected, or handed
 * to whoever asks why a payment never appeared. A counter tells you that you
 * lost something. A dead-letter topic tells you what.
 *
 * <p>What lands on the topic depends on which of two failures happened, and the
 * difference is worth stating rather than glossing:
 *
 * <ul>
 *   <li><b>Bytes that did not deserialise</b> are republished <em>verbatim</em>.
 *       The recoverer digs the original payload out of the deserializer's own
 *       exception header, because by the time anything else sees the record its
 *       value is already null. This is the case where byte fidelity matters most
 *       — the service could not read the message, so any rendering of it would be
 *       a guess — and it is the case where it is actually achieved.</li>
 *   <li><b>A transaction that parsed and then failed to process</b> is
 *       republished as that parsed transaction, serialised to JSON. This is
 *       <em>not</em> the original bytes: a field the producer sent that is not
 *       part of the contract has already been dropped by the deserializer, and
 *       every field of the contract the producer omitted is present and null.
 *       What survives is the contract, which is what a replay would act on.</li>
 * </ul>
 *
 * <p>Both cases carry {@code kafka_dlt-*} headers naming the original topic,
 * partition, offset and the exception. A dead letter with no cause attached is a
 * message in a folder nobody can act on.
 */
@Configuration
public class DeadLetterConfig {

    /**
     * Three attempts, a second apart.
     *
     * <p>Not zero, and not ten. Zero would send a transaction to the dead-letter
     * topic because the database was briefly unavailable, which turns a blip
     * into a manual recovery job. Ten would hold the partition for the length of
     * the outage while every retry fails for the same reason. Two seconds is
     * long enough to survive a connection being re-established and short enough
     * that a stream of bad records cannot become a stalled consumer.
     *
     * <p>This buys nothing for a malformed message, which is why those are
     * classified as not retryable below and go straight to the topic.
     */
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long RETRIES = 2L;

    /** Matches the vocabulary the failure counters already use. */
    private static final String REASON_DESERIALIZATION = "deserialization";
    private static final String REASON_PROCESSING = "processing";

    /**
     * A producer that can write both shapes of dead letter.
     *
     * <p>The two failures produce different values. A message that could not be
     * deserialised is recovered as the original {@code byte[]}; a message that
     * deserialised and then failed to process is a {@link Transaction}, which
     * has to be serialised as JSON to be readable on the topic. One serialiser
     * cannot do both, so the type decides — which is what
     * {@link DelegatingByTypeSerializer} is for.
     *
     * <p>{@link KafkaConnectionDetails} is consulted for the same reason the
     * outbox producer consults it: a broker whose address is allocated at
     * runtime, by Testcontainers or compose support, is not in the properties,
     * and a producer that reads only the property dials the wrong broker while
     * everything around it works.
     */
    @Bean
    public ProducerFactory<Object, Object> deadLetterProducerFactory(
            KafkaProperties properties,
            ObjectProvider<KafkaConnectionDetails> connectionDetails) {

        Map<String, Object> config = properties.buildProducerProperties();
        connectionDetails.ifAvailable(details ->
                config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        details.getProducer().getBootstrapServers()));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        DefaultKafkaProducerFactory<Object, Object> factory =
                new DefaultKafkaProducerFactory<>(config);
        factory.setValueSerializer(new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                Transaction.class, new JsonSerializer<>())));
        return factory;
    }

    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate(
            ProducerFactory<Object, Object> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    /**
     * Publishes to the dead-letter topic, then counts what was published.
     *
     * <p>In that order deliberately. Counting first would report a record as
     * dead-lettered when the publish is what failed, and a dead-letter path that
     * lies about having saved something is worse than not having one: the
     * exception from a failed publish propagates, the container retries, and the
     * record is still on the original topic to be recovered.
     *
     * <p>The counter is separate from {@code transactions.failed} rather than
     * replacing it, because they answer different questions. That one counts
     * failed attempts, retries included; this one counts records the engine gave
     * up on. Equal values mean retrying is buying nothing.
     */
    @Bean
    public DefaultErrorHandler transactionErrorHandler(
            KafkaTemplate<Object, Object> deadLetterKafkaTemplate, MeterRegistry meters) {

        // Registered at zero for the same reason the failure counters are: a
        // panel asking "how many records have we given up on?" must be able to
        // answer "none" rather than "No data", which reads like a broken query.
        meters.counter("transactions.dead.lettered", "reason", REASON_DESERIALIZATION);
        meters.counter("transactions.dead.lettered", "reason", REASON_PROCESSING);

        // Every failure from `transactions` lands on one partition of
        // `transactions.DLT`, rather than the framework default of mirroring the
        // source partition. The source has one partition today, so the default
        // would work — but it would break silently the moment the topic is
        // widened, by publishing to a partition the dead-letter topic does not
        // have.
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(Topics.TRANSACTIONS_DLT, 0));

        ConsumerRecordRecoverer counted = (record, exception) -> {
            publisher.accept(record, exception);
            meters.counter("transactions.dead.lettered", "reason", reasonFor(exception)).increment();
        };

        DefaultErrorHandler handler = new DefaultErrorHandler(
                counted, new FixedBackOff(RETRY_INTERVAL_MS, RETRIES));

        // A record that cannot be read will not become readable on the third
        // attempt. Retrying it spends two seconds of the partition's time to
        // reach the same conclusion, so these skip the backoff entirely.
        // DeserializationException is already in the framework's fatal set; the
        // one this service throws is not, and has to be named.
        handler.addNotRetryableExceptions(UnreadableRecordException.class);
        return handler;
    }

    /** Walks the cause chain, because the listener's exception wraps the real one. */
    private static String reasonFor(Exception exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof DeserializationException || t instanceof UnreadableRecordException) {
                return REASON_DESERIALIZATION;
            }
        }
        return REASON_PROCESSING;
    }
}
