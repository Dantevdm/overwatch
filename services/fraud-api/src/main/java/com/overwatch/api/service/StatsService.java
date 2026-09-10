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

    /** Default window when the caller does not ask for one: the trailing day. */
    public static final int DEFAULT_RANGE_MINUTES = 24 * 60;

    /**
     * Longest window the dashboard will aggregate — seven days, matching the cap
     * the replay endpoint already applies. Both are bounded for the same reason:
     * an unbounded range is a full table scan someone triggers by editing a URL.
     */
    public static final int MAX_RANGE_MINUTES = 7 * 24 * 60;

    /**
     * Bucket widths the series may use, in seconds, ascending.
     *
     * <p>A fixed ladder of round numbers rather than {@code range / n}. Buckets
     * that fall on whole seconds, minutes and hours put boundaries where a reader
     * expects them — 14:30:00, not 14:27:43 — and keep the axis labels short.
     */
    private static final long[] BUCKET_LADDER = {
            10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 21600, 43200, 86400
    };

    /** Buckets to aim for. Enough to show a shape, few enough to stay readable. */
    private static final int TARGET_BUCKETS = 40;

    private final AlertRepository alerts;
    private final TransactionReadRepository transactions;
    private final RuleRepository rules;

    public StatsService(AlertRepository alerts, TransactionReadRepository transactions,
                        RuleRepository rules) {
        this.alerts = alerts;
        this.transactions = transactions;
        this.rules = rules;
    }

    /**
     * Clamp a requested window to something the server will actually aggregate.
     * Anything absent, zero or negative falls back to the default rather than
     * being treated as "all of history".
     */
    public static int clampRange(Integer rangeMinutes) {
        if (rangeMinutes == null || rangeMinutes <= 0) {
            return DEFAULT_RANGE_MINUTES;
        }
        return Math.min(rangeMinutes, MAX_RANGE_MINUTES);
    }

    /**
     * The narrowest ladder width that keeps the series under {@link #TARGET_BUCKETS}
     * points. Falls back to the widest rung if even a day per bucket is not enough,
     * which the range cap makes unreachable but leaves the method total.
     */
    static long bucketSecondsFor(int rangeMinutes) {
        long rangeSeconds = (long) rangeMinutes * 60;
        for (long width : BUCKET_LADDER) {
            if (rangeSeconds / width <= TARGET_BUCKETS) {
                return width;
            }
        }
        return BUCKET_LADDER[BUCKET_LADDER.length - 1];
    }

    /** Floor an instant to its bucket boundary, measured from the Unix epoch. */
    private static Instant binned(Instant t, long bucketSeconds) {
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), bucketSeconds) * bucketSeconds);
    }

    @Transactional(readOnly = true)
    public DashboardStats dashboard() {
        return dashboard(DEFAULT_RANGE_MINUTES);
    }

    @Transactional(readOnly = true)
    public DashboardStats dashboard(int requestedRangeMinutes) {
        int rangeMinutes = clampRange(requestedRangeMinutes);
        long bucketSeconds = bucketSecondsFor(rangeMinutes);

        Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant dayAgo = Instant.now().minus(24, ChronoUnit.HOURS);
        Instant rangeStart = Instant.now().minus(rangeMinutes, ChronoUnit.MINUTES);

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

        List<Instant> boundaries = bucketBoundaries(rangeStart, bucketSeconds);
        List<TimeBucket> series = densify(alerts.bucketedCounts(rangeStart, bucketSeconds), boundaries);
        List<TimeBucket> volume = densify(transactions.bucketedCounts(rangeStart, bucketSeconds), boundaries);

        return new DashboardStats(
                transactions.count(),
                transactions.countByOccurredAtAfter(hourAgo),
                alerts.count(),
                alerts.countByStatus("OPEN"),
                Optional.ofNullable(alerts.averageRiskScore()).orElse(0.0),
                Optional.ofNullable(alerts.totalFlaggedSince(dayAgo)).orElse(BigDecimal.ZERO),
                bySeverity, byCategory,
                rangeMinutes, bucketSeconds,
                series, volume, severitySeries(rangeStart, bucketSeconds));
    }

    /**
     * Turn sparse {@code (bucket, count)} rows into one entry per boundary.
     *
     * <p>Dense, for the same reason {@link #severitySeries} is dense: a gap drawn
     * as a straight line between two distant points invents a trend, and a quiet
     * hour is a fact worth drawing.
     */
    private static List<TimeBucket> densify(List<Object[]> rows, List<Instant> boundaries) {
        Map<Instant, Long> counted = new HashMap<>();
        for (Object[] row : rows) {
            counted.put(toInstant(row[0]), ((Number) row[1]).longValue());
        }
        return boundaries.stream()
                .map(b -> new TimeBucket(b, counted.getOrDefault(b, 0L)))
                .toList();
    }

    /**
     * Every bucket boundary in the window, oldest first. Computed in Java with the
     * same epoch origin {@code date_bin} uses in SQL, so a boundary here always
     * matches a boundary there and the zero-fill lines up instead of doubling
     * buckets up by a fraction of a width.
     */
    private static List<Instant> bucketBoundaries(Instant since, long bucketSeconds) {
        List<Instant> out = new ArrayList<>();
        Instant end = binned(Instant.now(), bucketSeconds);
        for (Instant b = binned(since, bucketSeconds); !b.isAfter(end); b = b.plusSeconds(bucketSeconds)) {
            out.add(b);
        }
        return out;
    }

    private static Instant toInstant(Object value) {
        return value instanceof Timestamp ts ? ts.toInstant() : (Instant) value;
    }

    /**
     * Alerts per bucket split by severity, over the selected window.
     *
     * <p>Dense, not sparse: every bucket in the window is present even when
     * nothing fired. A line chart built from sparse buckets connects two points a
     * long way apart across a quiet stretch, drawing a slope that says traffic
     * declined gradually when in fact it stopped. Zeros make the quiet visible.
     */
    private List<SeverityBucket> severitySeries(Instant since, long bucketSeconds) {
        Map<Instant, Map<String, Long>> byBucket = new LinkedHashMap<>();
        for (Instant b : bucketBoundaries(since, bucketSeconds)) {
            Map<String, Long> zeros = new LinkedHashMap<>();
            SEVERITIES.forEach(s -> zeros.put(s, 0L));
            byBucket.put(b, zeros);
        }

        for (Object[] row : alerts.bucketedCountsBySeverity(since, bucketSeconds)) {
            Map<String, Long> counts = byBucket.get(toInstant(row[0]));
            if (counts != null) {                     // a row on the window boundary
                counts.put((String) row[1], ((Number) row[2]).longValue());
            }
        }

        return byBucket.entrySet().stream()
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
