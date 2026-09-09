package com.overwatch.common.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Maps to {@code fraud_alerts}. */
@Entity
@Table(name = "fraud_alerts")
public class FraudAlertEntity {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "risk_score", nullable = false, precision = 3, scale = 2)
    private BigDecimal riskScore;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(nullable = false, length = 16)
    private String status;

    /** Denormalised from the transaction so alert lists need no join. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // Hits are written and read as a unit with their alert, so cascading is
    // appropriate here rather than a separate repository call.
    @OneToMany(mappedBy = "alert", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlertRuleHitEntity> hits = new ArrayList<>();

    protected FraudAlertEntity() {
        // JPA
    }

    public FraudAlertEntity(UUID id, UUID transactionId, BigDecimal riskScore, String severity,
                            String status, BigDecimal amount, String currency) {
        this.id = id;
        this.transactionId = transactionId;
        this.riskScore = riskScore;
        this.severity = severity;
        this.status = status;
        this.amount = amount;
        this.currency = currency;
    }

    public void addHit(AlertRuleHitEntity hit) {
        hits.add(hit);
        hit.setAlert(this);
    }

    /**
     * Analyst disposition is the one field anything other than the engine writes.
     * CONFIRMED and CLEARED are what make a per-rule false-positive rate
     * computable at all.
     */
    public void setStatus(String status) { this.status = status; }

    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public BigDecimal getRiskScore() { return riskScore; }
    public String getSeverity() { return severity; }
    public String getStatus() { return status; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public List<AlertRuleHitEntity> getHits() { return hits; }
}
