package com.overwatch.engine.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps to {@code transactions}. The schema is owned by Flyway; Hibernate runs
 * {@code ddl-auto: validate}, so any drift between this class and the migration
 * fails at startup rather than corrupting data quietly.
 */
@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    private UUID id;

    @Column(name = "card_id", nullable = false, length = 64)
    private String cardId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "merchant_name", nullable = false)
    private String merchantName;

    @Column(name = "merchant_category", nullable = false, length = 64)
    private String merchantCategory;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(nullable = false, length = 16)
    private String channel;

    /** Named occurred_at rather than "timestamp", which is a reserved word. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> metadata = new HashMap<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected TransactionEntity() {
        // JPA
    }

    public TransactionEntity(UUID id, String cardId, BigDecimal amount, String currency,
                             String merchantName, String merchantCategory, String countryCode,
                             String channel, Instant occurredAt, Map<String, String> metadata) {
        this.id = id;
        this.cardId = cardId;
        this.amount = amount;
        this.currency = currency;
        this.merchantName = merchantName;
        this.merchantCategory = merchantCategory;
        this.countryCode = countryCode;
        this.channel = channel;
        this.occurredAt = occurredAt;
        this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
    }

    public UUID getId() { return id; }
    public String getCardId() { return cardId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getMerchantName() { return merchantName; }
    public String getMerchantCategory() { return merchantCategory; }
    public String getCountryCode() { return countryCode; }
    public String getChannel() { return channel; }
    public Instant getOccurredAt() { return occurredAt; }
    public Map<String, String> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
