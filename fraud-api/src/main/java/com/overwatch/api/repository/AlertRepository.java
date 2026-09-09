package com.overwatch.api.repository;

import com.overwatch.common.persistence.FraudAlertEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<FraudAlertEntity, UUID> {

    /**
     * Filtered alert list. Null parameters mean "no filter", which keeps this to
     * one query instead of a Specification tree for four optional filters.
     */
    @Query("""
            SELECT a FROM FraudAlertEntity a
            WHERE (:severity IS NULL OR a.severity = :severity)
              AND (:status   IS NULL OR a.status   = :status)
              AND (:since    IS NULL OR a.createdAt >= :since)
            ORDER BY a.createdAt DESC
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

    @Query("""
            SELECT COALESCE(SUM(a.amount), 0) FROM FraudAlertEntity a
            WHERE a.createdAt >= :since
            """)
    java.math.BigDecimal totalFlaggedSince(@Param("since") Instant since);

    @Query("SELECT COALESCE(AVG(a.riskScore), 0) FROM FraudAlertEntity a")
    Double averageRiskScore();

    /**
     * Alerts per hour, split by severity, for the dashboard's severity trend.
     *
     * <p>Returned long rather than pivoted in SQL: a crosstab would need the four
     * severity names baked into the statement, and severities are an enum the
     * application already knows. Pivoting in {@code StatsService} keeps the query
     * indifferent to how many severities exist.
     */
    @Query(value = """
            SELECT date_trunc('hour', created_at) AS bucket, severity, COUNT(*)
            FROM fraud_alerts
            WHERE created_at >= :since
            GROUP BY bucket, severity ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> hourlyCountsBySeverity(@Param("since") Instant since);

    /** Alerts per hour for the dashboard's time series. */
    @Query(value = """
            SELECT date_trunc('hour', created_at) AS bucket, COUNT(*)
            FROM fraud_alerts
            WHERE created_at >= :since
            GROUP BY bucket ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> hourlyCounts(@Param("since") Instant since);
}
