package com.overwatch.engine.persistence.repository;

import com.overwatch.common.persistence.OutboxEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    /**
     * The next batch to publish, claimed for this poller alone.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes more than one engine
     * instance safe. A pessimistic write lock alone would make the second
     * instance wait for the first one's batch; skipping the locked rows makes it
     * take the next ones instead, so two pollers share the backlog rather than
     * queue behind each other. Without either, both would read the same rows and
     * publish every message twice.
     *
     * <p>Ordered by id, so messages leave in the order they were committed.
     * Kafka only guarantees ordering within a partition and the key is the
     * transaction id, so what this actually preserves is per-transaction
     * ordering — which is the ordering that means anything here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT o FROM OutboxEntity o WHERE o.publishedAt IS NULL ORDER BY o.id")
    List<OutboxEntity> claimPending(Limit limit);

    /** How far behind the outbox is, in messages. Exported as a gauge. */
    long countByPublishedAtIsNull();

    /**
     * The oldest thing still waiting.
     *
     * <p>Depth alone cannot distinguish a busy minute from a stuck poller: a
     * hundred messages published within a second of arriving is healthy, and one
     * message sitting for ten minutes is not.
     */
    @Query("SELECT MIN(o.createdAt) FROM OutboxEntity o WHERE o.publishedAt IS NULL")
    Instant oldestPending();

    /**
     * Drop published rows past their retention.
     *
     * <p>An outbox that is never pruned is a full copy of every alert ever
     * raised, in a table whose only purpose is to hold things briefly. Pruning
     * on published_at rather than created_at means a message that sat in a
     * backlog for an hour still gets its full retention window after it finally
     * goes out.
     */
    @Modifying
    @Query("DELETE FROM OutboxEntity o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
