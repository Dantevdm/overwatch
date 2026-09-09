package com.overwatch.engine.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Maps to {@code fraud_rules} — the configuration that makes rules tunable. */
@Entity
@Table(name = "fraud_rules")
public class FraudRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_type", nullable = false, length = 64)
    private String ruleType;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 16)
    private String state;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal weight;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> parameters = new HashMap<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected FraudRuleEntity() {
        // JPA
    }

    public Long getId() { return id; }
    public String getRuleType() { return ruleType; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getState() { return state; }
    public BigDecimal getWeight() { return weight; }
    public Map<String, Object> getParameters() { return parameters; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
