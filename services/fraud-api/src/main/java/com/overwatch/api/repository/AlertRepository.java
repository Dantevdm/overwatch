package com.overwatch.api.repository;

import com.overwatch.common.persistence.FraudAlertEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<FraudAlertEntity, UUID> {

    /**
     * Filtered alert list. A null string parameter means "no filter", which keeps
     * this to one query instead of a Specification tree for four optional filters.
     *
     * <p>{@code since} is deliberately not nullable — see
     * {@link TransactionReadRepository#search} for why a nullable timestamp
     * parameter cannot work here. "No lower bound" is {@link Instant#EPOCH}.
     *
     * <p>Windowed and ordered on {@code occurredAt} rather than {@code createdAt},
     * so "alerts from the last hour" means an hour of card activity and not an
     * hour of engine uptime. The whole API is consistent on this: every window
     * over alerts or transactions is a window over when things happened.
     */
    @Query("""
            SELECT a FROM FraudAlertEntity a
            WHERE (:severity IS NULL OR a.severity = :severity)
              AND (:status   IS NULL OR a.status   = :status)
              AND a.occurredAt >= :since
            ORDER BY a.occurredAt DESC
            """)
    Page<FraudAlertEntity> search(@Param("severity") String severity,
                                  @Param("status") String status,
                                  @Param("since") Instant since,
                                  Pageable pageable);

    /** Fetches hits eagerly — an alert without its reasons is not much use. */
    @Query("SELECT a FROM FraudAlertEntity a LEFT JOIN FETCH a.hits WHERE a.id = :id")
    Optional<FraudAlertEntity> findByIdWithHits(@Param("id") UUID id);

    long countByStatus(String status);

    @Query("SELECT a.severity, COUNT(a) FROM FraudAlertEntity a GROUP BY a.severity")
    List<Object[]> countBySeverity();

    /**
     * Every alert raised against one cardholder's transactions, newest first.
     *
     * <p>A subquery rather than a join, because there is no association to join
     * on: an alert references a transaction id, and transactions carry the
     * cardholder. Modelling a relationship between them would be modelling
     * something this system does not own — see V4 on why there is no customers
     * table — and the subquery reads exactly as the question is asked.
     *
     * <p>Hits are fetched with it. A profile that lists an alert without saying
     * which rules fired tells an investigator something happened without saying
     * what, which is the same failure the single-alert endpoint exists to avoid.
     */
    @Query("""
            SELECT DISTINCT a FROM FraudAlertEntity a
            LEFT JOIN FETCH a.hits
            WHERE a.transactionId IN (
                SELECT t.id FROM TransactionEntity t WHERE t.customerId = :customerId)
            ORDER BY a.occurredAt DESC
            """)
    List<FraudAlertEntity> findByCustomer(@Param("customerId") String customerId,
                                          Pageable pageable);

    @Query("""
            SELECT a.severity, COUNT(a) FROM FraudAlertEntity a
            WHERE a.transactionId IN (
                SELECT t.id FROM TransactionEntity t WHERE t.customerId = :customerId)
            GROUP BY a.severity
            """)
    List<Object[]> countBySeverityForCustomer(@Param("customerId") String customerId);

    /**
     * Alert counts for a page of cardholders, in one query.
     *
     * <p>The directory used to call {@link #countBySeverityForCustomer} once per
     * row. Each call is a few milliseconds, which looked harmless and was a
     * textbook N+1: ten rows meant eleven queries and about 70ms of the
     * directory's response time, and asking for fifty rows made it worse in
     * exact proportion to how much the reader wanted to see.
     *
     * <p>An explicit join rather than the {@code IN (SELECT ...)} the single-
     * customer version uses, because the grouping key lives on the transaction
     * and a subquery cannot be grouped by. There is no association between the
     * two entities to navigate -- an alert holds a transaction id, and that is
     * deliberate, see V4 -- so the join condition is spelled out.
     */
    @Query("""
            SELECT t.customerId, COUNT(a)
            FROM FraudAlertEntity a
            JOIN TransactionEntity t ON t.id = a.transactionId
            WHERE t.customerId IN :customerIds
            GROUP BY t.customerId
            """)
    List<Object[]> countByCustomers(@Param("customerIds") Collection<String> customerIds);

    @Query("""
            SELECT COALESCE(SUM(a.amount), 0) FROM FraudAlertEntity a
            WHERE a.occurredAt >= :since
            """)
    java.math.BigDecimal totalFlaggedSince(@Param("since") Instant since);

    @Query("SELECT COALESCE(AVG(a.riskScore), 0) FROM FraudAlertEntity a")
    Double averageRiskScore();

    /**
     * Alerts per bucket, split by severity, for the dashboard's severity trend.
     *
     * <p>Returned long rather than pivoted in SQL: a crosstab would need the four
     * severity names baked into the statement, and severities are an enum the
     * application already knows. Pivoting in {@code StatsService} keeps the query
     * indifferent to how many severities exist.
     *
     * <p>Bucketed with {@code date_bin} rather than {@code date_trunc} because the
     * dashboard's window is selectable down to five minutes, and {@code date_trunc}
     * only understands fixed calendar units — an hourly bucket over a five-minute
     * window is a single bar. {@code date_bin} takes an arbitrary width, so one
     * query serves every range.
     *
     * <p>The width arrives as a number cast to {@code double precision} and is
     * turned into an interval by {@code make_interval}, rather than being
     * interpolated as text. That keeps the parameter explicitly typed — the same
     * discipline {@link TransactionReadRepository#search} documents at length.
     *
     * <p>Bucketed on {@code occurred_at}, not {@code created_at}: the reader is
     * asking when the card was being used, not when the engine got round to the
     * message. The two agree to within a second on live traffic and disagree by
     * weeks after a replay or a history seed, which is exactly the case where
     * charting the write time collapses the whole backlog into one bucket.
     *
     * <p>Binned from the Unix epoch so bucket boundaries are absolute rather than
     * relative to when the query ran. Two calls a second apart therefore return
     * the same buckets, and {@code StatsService} can compute the identical
     * boundaries in Java when it fills the quiet ones with zeros.
     */
    @Query(value = """
            SELECT date_bin(make_interval(0, 0, 0, 0, 0, 0, CAST(:bucketSeconds AS double precision)),
                            occurred_at, TIMESTAMPTZ 'epoch') AS bucket,
                   severity, COUNT(*)
            FROM fraud_alerts
            WHERE occurred_at >= :since
            GROUP BY bucket, severity ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> bucketedCountsBySeverity(@Param("since") Instant since,
                                            @Param("bucketSeconds") long bucketSeconds);

    /** Alerts per bucket for the dashboard's time series. See above on bucketing. */
    @Query(value = """
            SELECT date_bin(make_interval(0, 0, 0, 0, 0, 0, CAST(:bucketSeconds AS double precision)),
                            occurred_at, TIMESTAMPTZ 'epoch') AS bucket,
                   COUNT(*)
            FROM fraud_alerts
            WHERE occurred_at >= :since
            GROUP BY bucket ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> bucketedCounts(@Param("since") Instant since,
                                  @Param("bucketSeconds") long bucketSeconds);
}
