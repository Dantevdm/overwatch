package com.overwatch.api.streams;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The Kafka clients the Streams page needs.
 *
 * <p>Declared rather than taken from auto-configuration. Boot's own beans are
 * typed {@code KafkaTemplate<?, ?>} and {@code DefaultKafkaConsumerFactory<?, ?>},
 * and injecting those as {@code <String, String>} relies on generic resolution
 * that is not guaranteed to match — a failure that shows up at startup, in a
 * message about no qualifying bean, for a reason that has nothing to do with
 * Kafka. Two explicit beans cost less than that.
 */
@Configuration
public class StreamsConfig {

    /**
     * Metadata client for topics, partitions, offsets and consumer groups.
     *
     * <p>Built from {@link KafkaAdmin}'s resolved configuration, so it follows the
     * same {@code spring.kafka.bootstrap-servers} everything else does rather than
     * carrying a second copy of it.
     *
     * <p>A single long-lived instance: {@code Admin} is thread-safe, pools its
     * connections, and creating one per request would open and close a broker
     * connection every time the page polls. It connects lazily, so a broker that
     * is down at startup does not stop this service from booting — the endpoints
     * fail, the rest of the API does not.
     */
    @Bean(destroyMethod = "close")
    public Admin streamsAdmin(KafkaAdmin kafkaAdmin) {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, "fraud-api-streams");
        // Bounded, and short. These calls sit behind a page that polls: a broker
        // that has gone away must surface as an error in a couple of seconds, not
        // as a request that hangs until the browser gives up.
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 4_000);
        return Admin.create(config);
    }

    /**
     * Producer for the two stream operations, publishing raw strings.
     *
     * <p>Values are strings because both operations publish bytes that already
     * exist — the JSON typed on the page, or a record read back off the topic —
     * and anything that re-serialises them makes the page a description of what
     * was sent rather than the thing itself.
     */
    @Bean
    public KafkaTemplate<String, String> rawKafkaTemplate(KafkaProperties properties) {
        ProducerFactory<String, String> factory =
                new DefaultKafkaProducerFactory<>(properties.buildProducerProperties());
        return new KafkaTemplate<>(factory);
    }
}
