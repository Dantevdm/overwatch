package com.overwatch.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Maps to {@code cardholder_summary}, the materialised view V7 introduces.
 *
 * <p>{@link Immutable} because it is a view: Hibernate will not attempt to
 * flush changes to it, and a stray setter cannot produce a runtime error about
 * updating a relation that has no updatable columns. Refreshing it is a
 * database operation, not an entity one — see the refresher in the API.
 *
 * <p>The identifier is the cardholder reference rather than a surrogate key. The
 * view has no key of its own to offer, and the reference is what every other
 * screen already navigates by.
 */
@Entity
@Immutable
@Table(name = "cardholder_summary")
public class CardholderSummaryEntity {

    @Id
    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "customer_name", length = 128)
    private String customerName;

    @Column(nullable = false)
    private long transactions;

    @Column(name = "total_spend", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalSpend;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Column(nullable = false)
    private int cards;

    protected CardholderSummaryEntity() {
        // JPA
    }

    public String getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public long getTransactions() { return transactions; }
    public BigDecimal getTotalSpend() { return totalSpend; }
    public Instant getLastSeen() { return lastSeen; }
    public int getCards() { return cards; }
}
