package com.overwatch.engine.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

/**
 * A producer that sends the outbox payload exactly as it was stored.
 *
 * <p>The application's default template serialises with {@code JsonSerializer},
 * which is right for a domain object and wrong for this: the outbox payload is
 * already JSON text, and handing a String to a JSON serialiser produces a
 * quoted JSON <em>string</em> containing the document — {@code "{\"id\":…}"}
 * rather than {@code {"id":…}}. Consumers would parse that as a string and
 * every field would be gone.
 *
 * <p>So the outbox gets its own factory with a plain {@link StringSerializer}
 * and the stored document reaches the topic as the JSON it already is. That is
 * the property the outbox is built on: what gets published is what the
 * committing transaction decided, not a re-rendering of it.
 *
 * <p>Declaring these beans makes Boot's own producer auto-configuration back
 * off, so this is the engine's <em>only</em> producer. That is deliberate and
 * not an accident of ordering: since the outbox took over publishing, nothing in
 * this service sends a domain object, and a second template that serialised one
 * would only be there for something to use by mistake. The consumer side is
 * untouched — it has its own factory and still deserialises into Transaction.
 */
@Configuration
public class OutboxKafkaConfig {

    /**
     * Inherits every configured producer property — acks, batching, security —
     * and overrides only the serialisers. Building the map by hand instead would
     * mean this producer quietly ignored any broker setting added to
     * application.yml later.
     *
     * <p>{@link KafkaConnectionDetails} is consulted for the bootstrap servers
     * where one exists, and it is not a nicety: those details are how Boot
     * reports a broker whose address is not in the configuration — a
     * Testcontainers service connection, or compose support, both of which
     * allocate a port at runtime. Reading only the property left this producer
     * dialling the hard-coded localhost:19092 default while everything else in
     * the context talked to the container, and the symptom was a pipeline that
     * persisted alerts and published none of them.
     */
    @Bean
    public ProducerFactory<String, String> outboxProducerFactory(
            KafkaProperties properties,
            ObjectProvider<KafkaConnectionDetails> connectionDetails) {

        Map<String, Object> config = properties.buildProducerProperties();
        connectionDetails.ifAvailable(details ->
                config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        details.getProducer().getBootstrapServers()));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }
}
