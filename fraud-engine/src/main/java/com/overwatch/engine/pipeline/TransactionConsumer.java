package com.overwatch.engine.pipeline;

import com.overwatch.common.Topics;
import com.overwatch.common.domain.Transaction;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the transaction stream.
 *
 * <p>Deliberately thin: it is the transport boundary and nothing else. All the
 * behaviour lives in {@link TransactionProcessor}, which is what lets the pipeline
 * be tested without a broker.
 */
@Component
public class TransactionConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

    private final TransactionProcessor processor;
    private final MeterRegistry meters;

    public TransactionConsumer(TransactionProcessor processor, MeterRegistry meters) {
        this.processor = processor;
        this.meters = meters;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "fraud-engine")
    public void onTransaction(Transaction txn) {
        // A null payload means ErrorHandlingDeserializer could not read the record.
        // Without that wrapper the container would fail before reaching this method
        // and retry the same record forever, which looks like a stalled pipeline
        // rather than a bad message.
        if (txn == null) {
            log.warn("Skipping a record that could not be deserialized");
            meters.counter("transactions.failed", "reason", "deserialization").increment();
            return;
        }
        try {
            processor.process(txn);
        } catch (RuntimeException e) {
            // One poison message must not stall the partition. A dead-letter topic
            // belongs here in production; recorded as a known gap rather than
            // half-built.
            log.error("Failed to process transaction {}; skipping", txn.id(), e);
            meters.counter("transactions.failed", "reason", "processing").increment();
        }
    }
}
