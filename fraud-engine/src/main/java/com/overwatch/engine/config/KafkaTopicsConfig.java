package com.overwatch.engine.config;

import com.overwatch.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics rather than relying on broker auto-creation.
 *
 * <p>Auto-creation is a broker setting that may be off, and when it is, the
 * symptom is not an error — the producer succeeds, the consumer sits idle, and
 * the dashboard simply stays empty with nothing in the logs to explain it.
 * Declaring them makes the topology explicit and the failure loud.
 *
 * <p>One partition each: ordering per card is what the velocity rule depends on,
 * and messages are keyed by card, so a single partition keeps that guarantee
 * trivially true. Scaling out would mean more partitions and relying on the key
 * to keep a card's events together — correct, but worth doing deliberately
 * rather than by default.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopic transactionsTopic() {
        return TopicBuilder.name(Topics.TRANSACTIONS).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic fraudAlertsTopic() {
        return TopicBuilder.name(Topics.FRAUD_ALERTS).partitions(1).replicas(1).build();
    }
}
