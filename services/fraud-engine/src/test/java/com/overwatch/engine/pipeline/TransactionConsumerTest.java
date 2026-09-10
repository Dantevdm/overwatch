package com.overwatch.engine.pipeline;

import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Transaction;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transport boundary. Thin, but not behaviour-free: both of its branches
 * exist to stop one bad record from looking like a stalled pipeline, and that
 * is a claim worth holding to a test rather than to a comment.
 */
class TransactionConsumerTest {

    private TransactionProcessor processor;
    private MeterRegistry meters;
    private TransactionConsumer consumer;

    @BeforeEach
    void setUp() {
        processor = mock(TransactionProcessor.class);
        meters = new SimpleMeterRegistry();
        consumer = new TransactionConsumer(processor, meters);
    }

    private static Transaction txn() {
        return new Transaction(UUID.randomUUID(), "CARD-1", new BigDecimal("100.00"), "ZAR",
                "Checkers", "groceries", "ZA", Channel.POS, Instant.now(), Map.of());
    }

    private double failed(String reason) {
        var c = meters.find("transactions.failed").tags("reason", reason).counter();
        return c == null ? -1.0 : c.count();
    }

    @Test
    @DisplayName("both failure counters exist at zero, so a healthy pipeline is not 'No data'")
    void failureCountersAreRegisteredAtZero() {
        assertThat(failed("deserialization")).isZero();
        assertThat(failed("processing")).isZero();
    }

    @Test
    @DisplayName("a good record is handed to the processor")
    void goodRecordIsProcessed() {
        Transaction t = txn();
        when(processor.process(t)).thenReturn(Optional.empty());

        consumer.onTransaction(t);

        verify(processor).process(t);
        assertThat(failed("deserialization")).isZero();
        assertThat(failed("processing")).isZero();
    }

    @Test
    @DisplayName("a null payload is counted as a deserialization failure and never reaches the processor")
    void nullPayloadIsCountedNotProcessed() {
        // ErrorHandlingDeserializer hands us null when it cannot read the record.
        // Without that wrapper the container fails before this method and retries
        // the same record forever, which reads as a stalled pipeline.
        consumer.onTransaction(null);

        verify(processor, never()).process(any());
        assertThat(failed("deserialization")).isEqualTo(1.0);
        assertThat(failed("processing")).isZero();
    }

    @Test
    @DisplayName("a poison message is counted and swallowed, so it cannot stall the partition")
    void poisonMessageDoesNotStallThePartition() {
        Transaction t = txn();
        when(processor.process(t)).thenThrow(new IllegalStateException("bad rule parameters"));

        // The exception must not escape: escaping means the container redelivers
        // the same record indefinitely and the partition stops moving.
        assertThatCode(() -> consumer.onTransaction(t)).doesNotThrowAnyException();

        assertThat(failed("processing")).isEqualTo(1.0);
        assertThat(failed("deserialization")).isZero();
    }
}
