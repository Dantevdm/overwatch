package com.overwatch.api.report;

import com.overwatch.api.service.StatsService;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Assembles a report snapshot.
 *
 * <p>Thin on purpose. Every figure a report carries is already an endpoint the
 * dashboard reads, and recomputing them here with slightly different SQL is how
 * a report and the screen it was exported from start disagreeing. This goes
 * through the same {@link StatsService} the dashboard does.
 */
@Service
public class ReportService {

    private final StatsService stats;

    public ReportService(StatsService stats) {
        this.stats = stats;
    }

    public ReportData snapshot(Integer rangeMinutes) {
        int range = StatsService.clampRange(rangeMinutes);
        return new ReportData(
                Instant.now(),
                range,
                // Deliberately the uncached computation. The dashboard's two-second
                // cache is right for a tile that repaints every five seconds and
                // wrong for a document somebody will file: a report should be a
                // snapshot of now, not of up to two seconds ago.
                stats.computeDashboard(range),
                stats.rulePerformance());
    }
}
