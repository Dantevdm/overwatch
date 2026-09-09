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
 * Maps to {@code shadow_rule_hits}.
 *
 * <p>A shadow rule fires without raising an alert, so its hits have no alert to
 * attach to and land here instead. This table is what makes "what would this rule
 * have caught last week?" answerable before the rule is ever switched on.
 */
@Entity
@Table(name = "shadow_rule_hits")
public class ShadowRuleHitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_type", nullable = false, length = 64)
    private String ruleType;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal weight;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> evidence = new HashMap<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ShadowRuleHitEntity() {
        // JPA
    }

    public ShadowRuleHitEntity(UUID transactionId, Long ruleId, String ruleType,
                               BigDecimal weight, String reason, Map<String, Object> evidence) {
        this.transactionId = transactionId;
        this.ruleId = ruleId;
        this.ruleType = ruleType;
        this.weight = weight;
        this.reason = reason;
        this.evidence = evidence == null ? new HashMap<>() : new HashMap<>(evidence);
    }

    public Long getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public Long getRuleId() { return ruleId; }
    public String getRuleType() { return ruleType; }
    public BigDecimal getWeight() { return weight; }
    public String getReason() { return reason; }
    public Map<String, Object> getEvidence() { return evidence; }
    public Instant getCreatedAt() { return createdAt; }
}
