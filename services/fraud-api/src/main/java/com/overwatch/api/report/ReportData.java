package com.overwatch.api.report;

import com.overwatch.api.dto.Dtos.DashboardStats;
import com.overwatch.api.dto.Dtos.RulePerformance;

import java.time.Instant;
import java.util.List;

/**
 * Everything both writers draw from, gathered once.
 *
 * <p>The two formats exist because they answer different questions — a PDF is
 * read and a workbook is worked on — but they must not be able to disagree about
 * the numbers. Assembling the figures here, before either writer runs, is what
 * makes "the spreadsheet says 412 and the PDF says 419" impossible rather than
 * merely unlikely: there is one query pass and one snapshot of it.
 *
 * @param generatedAt when the snapshot was taken. On the page rather than only in
 *                    file metadata, because a report with no date on it is a
 *                    number somebody will quote six months from now.
 */
public record ReportData(
        Instant generatedAt,
        int rangeMinutes,
        DashboardStats stats,
        List<RulePerformance> rules) {

    /**
     * Copied on the way in, which is what makes this a snapshot rather than a
     * view. Two writers read this record and a third caller holds the list they
     * passed; a report whose figures could change between the workbook and the
     * PDF would be the one bug worth avoiding here.
     */
    public ReportData {
        rules = List.copyOf(rules);
    }

    /** The window, said the way a person would say it. */
    public String windowLabel() {
        if (rangeMinutes % (24 * 60) == 0) {
            int days = rangeMinutes / (24 * 60);
            return days == 1 ? "the last 24 hours" : "the last " + days + " days";
        }
        if (rangeMinutes % 60 == 0) {
            int hours = rangeMinutes / 60;
            return hours == 1 ? "the last hour" : "the last " + hours + " hours";
        }
        return "the last " + rangeMinutes + " minutes";
    }

    /**
     * Alert rate over the window as a percentage of transactions.
     *
     * <p>The single figure a fraud lead looks for first, and the one the
     * dashboard makes you compute in your head from two other tiles.
     */
    public double alertRatePercent() {
        long transactions = stats.totalTransactions();
        return transactions == 0 ? 0 : 100.0 * stats.totalAlerts() / transactions;
    }

    /** Rules that have fired at least once, worst false-positive rate first. */
    public List<RulePerformance> firedRules() {
        return rules.stream().filter(r -> r.timesFired() > 0).toList();
    }
}
