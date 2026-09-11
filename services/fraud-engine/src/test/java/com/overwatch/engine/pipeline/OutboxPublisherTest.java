package com.overwatch.engine.pipeline;

import com.overwatch.common.persistence.OutboxEntity;
import com.overwatch.engine.persistence.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drain side of the outbox.
 *
 * <p>What is worth asserting here is not that it sends — that is one line — but
 * what it does when a send goes wrong: whether the row stays claimable, whether
 * one bad message takes the batch with it, and whether a failure is visible on a
 * dashboard rather than only in a log.
 */
@DisplayName("Outbox publisher")
class OutboxPublisherTest {

    private OutboxRepository outbox;
    private KafkaTemplate<String, String> kafka;
    private MeterRegistry meters;
    private OutboxPublisher publisher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        outbox = mock(OutboxRepository.class);
        kafka = mock(KafkaTemplate.class);
        meters = new SimpleMeterRegistry();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        publisher = new OutboxPublisher(outbox, kafka, meters, 100, 60);
    }

    private static OutboxEntity message(String payload) {
        return new OutboxEntity(UUID.randomUUID(), "fraud-alerts", "txn-1", payload);
    }

    private double counter(String name) {
        return meters.find(name).counter().count();
    }

    @Test
    @DisplayName("sends the stored payload unchanged, on the stored topic and key")
    void sendsWhatWasStored() {
        OutboxEntity pending = message("{\"id\":\"a\"}");
        when(outbox.claimPending(any(Limit.class))).thenReturn(List.of(pending));

        publisher.publishPending();

        // The payload reaches the broker as the document the committing
        // transaction decided on. Re-serialising here would mean the message
        // published is a re-rendering of the one that was agreed.
        verify(kafka).send(eq("fraud-alerts"), eq("txn-1"), eq("{\"id\":\"a\"}"));
        assertThat(pending.getPublishedAt()).isNotNull();
        assertThat(counter("outbox.published")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a failed send leaves the row claimable, so the message is not lost")
    void failedSendLeavesTheRowPending() {
        OutboxEntity pending = message("{}");
        when(outbox.claimPending(any(Limit.class))).thenReturn(List.of(pending));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        publisher.publishPending();

        // Not published, so the next poll claims it again. This is the whole
        // reason the outbox exists: a lost send is a retry rather than a
        // detection nobody hears about.
        assertThat(pending.getPublishedAt()).isNull();
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getLastError()).contains("broker down");
        assertThat(counter("outbox.publish.failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("one bad message does not stop the ones behind it")
    void oneFailureDoesNotAbandonTheBatch() {
        OutboxEntity bad = message("{\"n\":1}");
        OutboxEntity good = message("{\"n\":2}");
        when(outbox.claimPending(any(Limit.class))).thenReturn(List.of(bad, good));
        when(kafka.send(anyString(), anyString(), eq("{\"n\":1}")))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("nope")));

        publisher.publishPending();

        assertThat(bad.getPublishedAt()).isNull();
        assertThat(good.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("an empty backlog costs one query and nothing else")
    void emptyBacklogSendsNothing() {
        when(outbox.claimPending(any(Limit.class))).thenReturn(List.of());

        publisher.publishPending();

        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("depth and age are exported, and exist at zero on a quiet pipeline")
    void backlogIsMeasured() {
        // Both gauges are registered in the constructor, so a pipeline that has
        // never fallen behind still exports the series — "no data" and "nothing
        // pending" must not look the same on the panel that watches this.
        when(outbox.countByPublishedAtIsNull()).thenReturn(0L);
        when(outbox.oldestPending()).thenReturn(null);

        assertThat(meters.find("outbox.pending").gauge()).isNotNull();
        assertThat(meters.find("outbox.pending").gauge().value()).isZero();
        assertThat(meters.find("outbox.oldest.pending.seconds").gauge().value()).isZero();
    }

    @Test
    @DisplayName("age is the wait of the oldest thing still queued")
    void ageMeasuresTheOldestPending() {
        // Depth alone cannot tell a busy second from a stuck poller: a hundred
        // messages published within a second of arriving is healthy, and one
        // message sitting for ten minutes is not.
        when(outbox.oldestPending()).thenReturn(Instant.now().minusSeconds(120));

        assertThat(meters.find("outbox.oldest.pending.seconds").gauge().value())
                .isBetween(119.0, 125.0);
    }

    @Test
    @DisplayName("pruning drops published rows past their retention, and only those")
    void pruningIsBoundedToPublishedRows() {
        publisher.prunePublished();

        // The repository method's WHERE clause carries "published_at IS NOT
        // NULL"; what is asserted here is that the cut-off is the retention
        // window and not, say, now() — which would delete a message published a
        // second ago and still in flight to a slow consumer.
        verify(outbox).deletePublishedBefore(
                org.mockito.ArgumentMatchers.argThat(before ->
                        before.isBefore(Instant.now().minusSeconds(3500))
                                && before.isAfter(Instant.now().minusSeconds(3700))));
    }
}
