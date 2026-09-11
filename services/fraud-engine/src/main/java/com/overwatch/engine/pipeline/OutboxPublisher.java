package com.overwatch.engine.pipeline;

import com.overwatch.common.persistence.OutboxEntity;
import com.overwatch.engine.persistence.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves committed messages from {@code alert_outbox} onto the topic.
 *
 * <p>This exists so that raising an alert and announcing it are one decision.
 * The engine used to call {@code kafka.send()} inside the transaction that saved
 * the alert, which meant a rollback after the send left downstream holding a
 * notification for an alert that does not exist, and a send failure after the
 * commit lost the notification entirely. Now the engine writes a row, the row
 * commits with the alert, and this publishes it afterwards.
 *
 * <p>Deliberately dull. It does not know what an alert is; it moves a stored
 * document from a table to a topic and records whether that worked. Everything
 * interesting about the message was decided by the transaction that wrote it.
 *
 * <p><strong>At-least-once.</strong> A record can reach the broker and this
 * process can die before the row is marked published, and the next poll will
 * send it again. That is the deliberate side of the trade: losing a message is
 * unrecoverable, sending one twice is something a consumer can be built to
 * absorb — and the alert carries a stable UUID precisely so that it can be.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /**
     * How long to wait for the broker on one record.
     *
     * <p>Bounded because this runs on a scheduler thread: an unbounded get()
     * against an unreachable broker holds the row lock and the scheduler slot
     * until the producer's own timeouts expire, and the backlog behind it grows
     * for the whole of that. Failing fast and retrying on the next poll is the
     * same outcome, sooner and without the pile-up.
     */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry meters;
    private final int batchSize;
    private final Duration retention;

    public OutboxPublisher(OutboxRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           MeterRegistry meters,
                           @Value("${overwatch.outbox.batch-size:100}") int batchSize,
                           @Value("${overwatch.outbox.retention-minutes:60}") long retentionMinutes) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.meters = meters;
        this.batchSize = batchSize;
        this.retention = Duration.ofMinutes(retentionMinutes);

        // At zero from startup, like every other failure counter here: "nothing
        // has ever failed to publish" and "this panel is broken" must not look
        // the same on a dashboard.
        meters.counter("outbox.published");
        meters.counter("outbox.publish.failed");

        // Depth and age, as gauges, because they are the two numbers that say
        // whether this is working. Depth alone cannot tell a busy second from a
        // stuck poller; age alone cannot tell one stuck message from a thousand.
        meters.gauge("outbox.pending", this, OutboxPublisher::pendingCount);
        meters.gauge("outbox.oldest.pending.seconds", this, OutboxPublisher::oldestPendingSeconds);
    }

    /**
     * Publish one batch.
     *
     * <p>fixedDelay, not fixedRate: the interval is measured from the end of the
     * previous run, so a slow batch against a struggling broker cannot have a
     * second run started on top of it.
     *
     * <p>The whole batch is one transaction, which is what holds the row locks
     * for the duration — two instances claim disjoint batches
     * ({@code SKIP LOCKED}) rather than racing for the same rows.
     */
    @Scheduled(fixedDelayString = "${overwatch.outbox.poll-millis:200}")
    @Transactional
    public void publishPending() {
        List<OutboxEntity> batch = outbox.claimPending(Limit.of(batchSize));
        if (batch.isEmpty()) return;

        for (OutboxEntity message : batch) {
            try {
                kafka.send(message.getTopic(), message.getMessageKey(), message.getPayload())
                        .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                message.published(Instant.now());
                meters.counter("outbox.published").increment();
            } catch (InterruptedException e) {
                // Restore the flag and stop the batch. Swallowing this leaves a
                // thread that has been asked to stop still working through a
                // backlog, which is how a graceful shutdown becomes a hung one.
                Thread.currentThread().interrupt();
                message.failed("interrupted");
                break;
            } catch (Exception e) {
                // One bad message must not block the ones behind it: the row
                // stays unpublished, the loop moves on, and the next poll tries
                // it again. Ordering within a key is preserved by the id order
                // of the claim; a message that keeps failing does hold up later
                // messages for the same transaction, which is the correct
                // trade — out-of-order alerts for one card are worse than late
                // ones.
                message.failed(e.getMessage());
                meters.counter("outbox.publish.failed").increment();
                log.warn("Outbox message {} for alert {} failed on attempt {}: {}",
                        message.getId(), message.getAggregateId(),
                        message.getAttempts(), e.toString());
            }
        }
        // Flushed by the transaction commit — the marks and the failure counts
        // land together with whatever else this batch changed.
    }

    /**
     * Drop published rows past their retention.
     *
     * <p>Hourly, and separate from the publish loop: pruning is housekeeping,
     * and a DELETE that scans is not something to put in the path of the thing
     * that has to keep up with the stream.
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS, initialDelay = 5)
    @Transactional
    public void prunePublished() {
        int removed = outbox.deletePublishedBefore(Instant.now().minus(retention));
        if (removed > 0) {
            log.info("Pruned {} published outbox rows older than {}", removed, retention);
        }
    }

    private double pendingCount() {
        return outbox.countByPublishedAtIsNull();
    }

    private double oldestPendingSeconds() {
        Instant oldest = outbox.oldestPending();
        return oldest == null ? 0 : Duration.between(oldest, Instant.now()).toMillis() / 1000d;
    }
}
