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
 *
 * <p>It counts failures and then rethrows them. That is a change from the
 * original, which caught and dropped: catching kept the partition moving, which
 * was the important half, but it also meant a record the engine could not handle
 * left nothing behind but a log line and a number. Rethrowing hands the record to
 * the container's error handler, which retries it and then publishes it to the
 * dead-letter topic — so the partition still keeps moving and the record still
 * exists. See {@code DeadLetterConfig}.
 */
@Component
public class TransactionConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

    /** Why a record was dropped. Both are registered up front — see below. */
    private static final String REASON_DESERIALIZATION = "deserialization";
    private static final String REASON_PROCESSING = "processing";

    private final TransactionProcessor processor;
    private final MeterRegistry meters;

    public TransactionConsumer(TransactionProcessor processor, MeterRegistry meters) {
        this.processor = processor;
        this.meters = meters;

        // Register the failure counters at zero rather than on first failure.
        // Micrometer creates a counter when it is first incremented, so a
        // never-failing pipeline exposes no transactions_failed_total at all —
        // and a Grafana panel asking "how many records have we dropped?" answers
        // "No data", which reads identically to a panel whose query is wrong.
        // Zero is the answer, and it is worth being able to alert on.
        meters.counter("transactions.failed", "reason", REASON_DESERIALIZATION);
        meters.counter("transactions.failed", "reason", REASON_PROCESSING);
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "fraud-engine")
    public void onTransaction(Transaction txn) {
        // A backstop, and measured to be one. A record that ErrorHandlingDeserializer
        // could not read does not reach this method at all in the current setup:
        // the container sees the deserializer's exception header and hands the
        // record straight to the error handler, which dead-letters it — verified
        // against a live stack, where a malformed record raised
        // transactions_dead_lettered_total and left transactions_failed_total at
        // zero. The branch stays because the wrapper can be configured to deliver
        // a null payload instead, and a silent `return` on that path would commit
        // the offset and lose the bytes.
        //
        // Thrown rather than returned for that reason, and as its own type so the
        // error handler can skip the retries: a record that cannot be parsed will
        // not parse on the third attempt.
        if (txn == null) {
            meters.counter("transactions.failed", "reason", REASON_DESERIALIZATION).increment();
            throw new UnreadableRecordException("A record on " + Topics.TRANSACTIONS
                    + " could not be deserialized");
        }
        try {
            processor.process(txn);
        } catch (RuntimeException e) {
            // Counted here and rethrown, so this counts failed *attempts* —
            // retries included — while the dead-letter counter counts the
            // records actually given up on. One poison message still cannot
            // stall the partition: the error handler retries it a fixed number
            // of times and then moves on.
            log.error("Failed to process transaction {}; handing it to the error handler",
                    txn.id(), e);
            meters.counter("transactions.failed", "reason", REASON_PROCESSING).increment();
            throw e;
        }
    }
}
