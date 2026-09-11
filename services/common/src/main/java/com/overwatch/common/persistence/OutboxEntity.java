package com.overwatch.common.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to {@code alert_outbox} — one message waiting to be published.
 *
 * <p>A row is written in the same database transaction as the alert it
 * describes, which is the entire point: the alert and the intent to announce it
 * commit together or not at all. See the migration for what that replaced.
 *
 * <p>The payload is stored already serialised. The poller is deliberately
 * ignorant of what it is sending — it moves bytes from a table to a topic, and
 * the decision about what those bytes mean was made by the code that had the
 * context to make it.
 */
@Entity
@Table(name = "alert_outbox")
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 128)
    private String messageKey;

    /**
     * The record as it will be sent.
     *
     * <p>Mapped as a JSON string rather than a {@code Map}, so nothing between
     * here and the broker deserialises the payload into objects it would then
     * have to re-serialise. What is published is the document this transaction
     * decided on, not a second rendering of it by whatever the publishing code
     * happens to know about an alert at drain time.
     *
     * <p>{@code jsonb}, not {@code text}, and the difference is worth being
     * precise about: jsonb stores a parsed document, so it normalises
     * whitespace and does not preserve key order. The bytes are therefore not
     * byte-identical to what was written — the document is. In exchange the
     * column rejects anything that is not valid JSON at write time, which is
     * the right place to find that out, and the backlog stays queryable when
     * someone needs to ask what is stuck in it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    protected OutboxEntity() {
        // JPA
    }

    public OutboxEntity(UUID aggregateId, String topic, String messageKey, String payload) {
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
    }

    /** Mark as on the topic. Set once; a published row is never un-published. */
    public void published(Instant when) {
        this.publishedAt = when;
        this.lastError = null;
    }

    /**
     * Record a failed attempt.
     *
     * <p>The message is truncated because it can be a broker stack trace, and a
     * column that grows without bound on every retry turns a broker outage into
     * a disk-space incident.
     */
    public void failed(String message) {
        this.attempts++;
        this.lastError = message == null ? null
                : message.substring(0, Math.min(message.length(), 500));
    }

    public Long getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
}
