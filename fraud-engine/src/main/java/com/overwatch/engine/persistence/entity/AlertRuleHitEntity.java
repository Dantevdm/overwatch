package com.overwatch.engine.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps to {@code alert_rule_hits} — one row per rule that contributed to an alert.
 *
 * <p>{@code ruleType} is stored alongside {@code ruleId} on purpose: hit history
 * has to remain readable after the rule row it came from is deleted.
 */
@Entity
@Table(name = "alert_rule_hits")
public class AlertRuleHitEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id", nullable = false)
    private FraudAlertEntity alert;

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

    protected AlertRuleHitEntity() {
        // JPA
    }

    public AlertRuleHitEntity(Long ruleId, String ruleType, BigDecimal weight,
                              String reason, Map<String, Object> evidence) {
        this.ruleId = ruleId;
        this.ruleType = ruleType;
        this.weight = weight;
        this.reason = reason;
        this.evidence = evidence == null ? new HashMap<>() : new HashMap<>(evidence);
    }

    void setAlert(FraudAlertEntity alert) {
        this.alert = alert;
    }

    public Long getId() { return id; }
    public Long getRuleId() { return ruleId; }
    public String getRuleType() { return ruleType; }
    public BigDecimal getWeight() { return weight; }
    public String getReason() { return reason; }
    public Map<String, Object> getEvidence() { return evidence; }
}
