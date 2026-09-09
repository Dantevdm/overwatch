package com.overwatch.api.service;

import com.overwatch.api.dto.Dtos.*;
import com.overwatch.api.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Aggregations behind the dashboard. */
@Service
public class StatsService {

    /** Ordinal, least to most severe. The order the chart legend and stack follow. */
    private static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final AlertRepository alerts;
    private final TransactionReadRepository transactions;
    private final RuleRepository rules;

    public StatsService(AlertRepository alerts, TransactionReadRepository transactions,
                        RuleRepository rules) {
        this.alerts = alerts;
        this.transactions = transactions;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public DashboardStats dashboard() {
        Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant dayAgo = Instant.now().minus(24, ChronoUnit.HOURS);

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (String s : SEVERITIES) {
            bySeverity.put(s, 0L);   // present with zero, so charts keep a stable shape
        }
        for (Object[] row : alerts.countBySeverity()) {
            bySeverity.put((String) row[0], ((Number) row[1]).longValue());
        }

        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Object[] row : transactions.countByCategory()) {
            byCategory.put((String) row[0], ((Number) row[1]).longValue());
        }

        List<TimeBucket> series = new ArrayList<>();
        for (Object[] row : alerts.hourlyCounts(dayAgo)) {
            Instant bucket = row[0] instanceof Timestamp ts ? ts.toInstant() : (Instant) row[0];
            series.add(new TimeBucket(bucket, ((Number) row[1]).longValue()));
        }

        return new DashboardStats(
                transactions.count(),
                transactions.countByOccurredAtAfter(hourAgo),
                alerts.count(),
                alerts.countByStatus("OPEN"),
                Optional.ofNullable(alerts.averageRiskScore()).orElse(0.0),
                Optional.ofNullable(alerts.totalFlaggedSince(dayAgo)).orElse(BigDecimal.ZERO),
                bySeverity, byCategory, series, severitySeries(dayAgo));
    }

    /**
     * Alerts per hour split by severity, over the trailing 24 hours.
     *
     * <p>Dense, not sparse: every hour in the window is present even when nothing
     * fired. A line chart built from sparse buckets connects two points an hour
     * apart across a quiet stretch, drawing a slope that says traffic declined
     * gradually when in fact it stopped. Zeros make the quiet visible.
     */
    private List<SeverityBucket> severitySeries(Instant since) {
        Instant start = since.truncatedTo(ChronoUnit.HOURS);
        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);

        Map<Instant, Map<String, Long>> byHour = new LinkedHashMap<>();
        for (Instant h = start; !h.isAfter(end); h = h.plus(1, ChronoUnit.HOURS)) {
            Map<String, Long> zeros = new LinkedHashMap<>();
            SEVERITIES.forEach(s -> zeros.put(s, 0L));
            byHour.put(h, zeros);
        }

        for (Object[] row : alerts.hourlyCountsBySeverity(since)) {
            Instant bucket = row[0] instanceof Timestamp ts ? ts.toInstant() : (Instant) row[0];
            Map<String, Long> counts = byHour.get(bucket.truncatedTo(ChronoUnit.HOURS));
            if (counts != null) {                     // a row on the window boundary
                counts.put((String) row[1], ((Number) row[2]).longValue());
            }
        }

        return byHour.entrySet().stream()
                .map(e -> new SeverityBucket(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Per-rule operational metrics.
     *
     * <p>This is the view that tells a fraud team whether a rule is earning its
     * place, and it is the first thing missing from a rules engine nobody
     * instrumented.
     */
    @Transactional(readOnly = true)
    public List<RulePerformance> rulePerformance() {
        Map<String, long[]> hits = new HashMap<>();          // type -> [count]
        for (Object[] r : rules.hitCountsByRuleType()) {
            hits.put((String) r[0], new long[]{((Number) r[1]).longValue()});
        }
        Map<String, long[]> disposition = new HashMap<>();   // type -> [confirmed, cleared]
        for (Object[] r : rules.dispositionByRuleType()) {
            disposition.put((String) r[0], new long[]{
                    r[1] == null ? 0 : ((Number) r[1]).longValue(),
                    r[2] == null ? 0 : ((Number) r[2]).longValue()});
        }
        Map<String, Long> shadow = new HashMap<>();
        for (Object[] r : rules.shadowHitCounts()) {
            shadow.put((String) r[0], ((Number) r[1]).longValue());
        }

        long totalAlerts = Math.max(1, alerts.count());   // avoid divide-by-zero

        return rules.findAllByOrderByWeightDesc().stream().map(rule -> {
            long fired = hits.getOrDefault(rule.getRuleType(), new long[]{0})[0];
            long[] d = disposition.getOrDefault(rule.getRuleType(), new long[]{0, 0});
            long reviewed = d[0] + d[1];

            // Null rather than zero when nothing has been reviewed: an unreviewed
            // rule reporting a 0% false-positive rate reads as a perfect rule.
            Double fpRate = reviewed == 0 ? null : (double) d[1] / reviewed;

            return new RulePerformance(
                    rule.getId(), rule.getRuleType(), rule.getName(), rule.getState(),
                    rule.getWeight(), fired, (double) fired / totalAlerts,
                    d[0], d[1], fpRate,
                    shadow.getOrDefault(rule.getRuleType(), 0L));
        }).toList();
    }
}
