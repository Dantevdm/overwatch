package com.overwatch.api.report;

import com.overwatch.api.dto.Dtos.RulePerformance;
import com.overwatch.api.dto.Dtos.TimeBucket;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.overwatch.api.report.PdfCanvas.BOLD;
import static com.overwatch.api.report.PdfCanvas.CONTENT_WIDTH;
import static com.overwatch.api.report.PdfCanvas.MARGIN;
import static com.overwatch.api.report.PdfCanvas.PAGE_HEIGHT;
import static com.overwatch.api.report.PdfCanvas.PAGE_WIDTH;
import static com.overwatch.api.report.PdfCanvas.REGULAR;
import static com.overwatch.api.report.PdfCharts.LABEL;
import static com.overwatch.api.report.PdfCharts.TEXT;

/**
 * The report as a document.
 *
 * <p>Three pages, in the order the questions get asked: what happened, how it
 * moved, and which rules did it. A reader who stops after page one should still
 * have the answer they came for, which is why the headline figures are above the
 * charts rather than after them.
 */
final class PdfReport {

    private static final int CAPITEC_BLUE = 0x009DE0;
    private static final int ALERT_RED = 0xDC2626;
    private static final int SLATE = 0x64748B;
    private static final int RULE = 0xE2E8F0;

    private static final int[] SEVERITY_COLOURS = {0x3B82F6, 0xF59E0B, 0xEF6C00, 0xDC2626};
    private static final int[] CATEGORY_COLOURS = {0x009DE0};

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter AXIS =
            DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault());

    /** Headline figures per row, and the vertical pitch of one. */
    private static final int TILES_PER_ROW = 3;
    private static final float TILE_HEIGHT = 58f;

    /** Rows of the rule table that fit below the header on a page of their own. */
    private static final int RULE_ROWS_PER_PAGE = 28;

    private PdfReport() {
    }

    static byte[] write(ReportData data) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            describe(document, data);
            overviewPage(document, data);
            activityPage(document, data);
            rulePages(document, data);
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Document properties, so the file identifies itself in a viewer's title bar
     * and in whatever document store it ends up in.
     */
    private static void describe(PDDocument document, ReportData data) {
        PDDocumentInformation info = document.getDocumentInformation();
        info.setTitle("Overwatch fraud detection report");
        info.setSubject("Covering " + data.windowLabel());
        info.setCreator("Overwatch");
        info.setProducer("Overwatch fraud-api");
    }

    // ----------------------------------------------------------------- pages

    private static void overviewPage(PDDocument document, ReportData data) throws IOException {
        try (PdfCanvas canvas = new PdfCanvas(document)) {
            float y = header(canvas, "Fraud detection report",
                    "Covering " + data.windowLabel() + ", generated " + STAMP.format(data.generatedAt()));

            y = figures(canvas, y, data);

            y += 10;
            canvas.text("Alerts by severity", MARGIN, y, BOLD, 11, TEXT);
            y += 16;
            Map<String, Long> severity = data.stats().alertsBySeverity();
            PdfCharts.horizontalBars(canvas, MARGIN, y, CONTENT_WIDTH, 90,
                    List.copyOf(severity.keySet()), List.copyOf(severity.values()), SEVERITY_COLOURS);
            y += 108;

            canvas.text("Transactions by merchant category", MARGIN, y, BOLD, 11, TEXT);
            y += 16;
            Map<String, Long> categories = topCategories(data.stats().transactionsByCategory());
            PdfCharts.horizontalBars(canvas, MARGIN, y, CONTENT_WIDTH,
                    Math.max(40, categories.size() * 20f),
                    List.copyOf(categories.keySet()), List.copyOf(categories.values()),
                    CATEGORY_COLOURS);

            footer(canvas, data, 1);
        }
    }

    private static void activityPage(PDDocument document, ReportData data) throws IOException {
        try (PdfCanvas canvas = new PdfCanvas(document)) {
            float y = header(canvas, "How the window moved",
                    "One point per " + humanBucket(data.stats().bucketSeconds())
                            + " across " + data.windowLabel() + ".");

            List<String> labels = data.stats().alertsOverTime().stream()
                    .map(b -> AXIS.format(b.bucket())).toList();

            canvas.text("Alerts raised", MARGIN, y, BOLD, 11, TEXT);
            y += 16;
            PdfCharts.lines(canvas, MARGIN, y, CONTENT_WIDTH, 170, labels,
                    List.of(new PdfCharts.Series("Alerts", ALERT_RED, counts(data.stats().alertsOverTime()))));
            y += 200;

            // A second chart rather than a second series on the first. Volume is
            // two or three orders of magnitude above the alert count, and on a
            // shared axis the alert line would lie flat on zero -- which is the
            // chart most dashboards ship and the reason nobody trusts it.
            canvas.text("Transactions processed", MARGIN, y, BOLD, 11, TEXT);
            y += 16;
            PdfCharts.lines(canvas, MARGIN, y, CONTENT_WIDTH, 170, labels,
                    List.of(new PdfCharts.Series("Transactions", SLATE,
                            counts(data.stats().transactionsOverTime()))));
            y += 196;

            severityLines(canvas, y, data, labels);

            footer(canvas, data, 2);
        }
    }

    private static void severityLines(PdfCanvas canvas, float y, ReportData data, List<String> labels)
            throws IOException {
        canvas.text("Severity mix", MARGIN, y, BOLD, 11, TEXT);
        y += 16;

        List<String> bands = List.copyOf(data.stats().alertsBySeverity().keySet());
        List<PdfCharts.Series> series = new ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            String band = bands.get(i);
            series.add(new PdfCharts.Series(band, SEVERITY_COLOURS[i % SEVERITY_COLOURS.length],
                    data.stats().severityOverTime().stream()
                            .map(b -> b.counts().getOrDefault(band, 0L)).toList()));
        }
        PdfCharts.lines(canvas, MARGIN, y, CONTENT_WIDTH, 120, labels, series);

        float x = MARGIN;
        for (PdfCharts.Series s : series) {
            x = PdfCharts.legend(canvas, x, y + 148, s.name(), s.colour());
        }
    }

    private static void rulePages(PDDocument document, ReportData data) throws IOException {
        List<RulePerformance> rules = data.rules();
        int page = 3;
        for (int offset = 0; offset < Math.max(1, rules.size()); offset += RULE_ROWS_PER_PAGE) {
            List<RulePerformance> slice = rules.subList(
                    Math.min(offset, rules.size()),
                    Math.min(offset + RULE_ROWS_PER_PAGE, rules.size()));
            try (PdfCanvas canvas = new PdfCanvas(document)) {
                float y = header(canvas, "Is every rule earning its place?",
                        "A blank false-positive rate means nothing has been reviewed yet. "
                                + "Zero would read as a perfect rule.");
                ruleTable(canvas, y, slice);
                footer(canvas, data, page++);
            }
        }
    }

    // ---------------------------------------------------------------- pieces

    private static float header(PdfCanvas canvas, String title, String subtitle) throws IOException {
        canvas.rect(0, 0, PAGE_WIDTH, 4, CAPITEC_BLUE);
        canvas.text("OVERWATCH", MARGIN, 42, BOLD, 9, CAPITEC_BLUE);
        canvas.text(title, MARGIN, 68, BOLD, 19, TEXT);
        canvas.text(subtitle, MARGIN, 86, REGULAR, 9.5f, LABEL);
        canvas.line(MARGIN, 100, PAGE_WIDTH - MARGIN, 100, RULE, 0.75f);
        return 124;
    }

    private static void footer(PdfCanvas canvas, ReportData data, int page) throws IOException {
        float y = PAGE_HEIGHT - 34;
        canvas.line(MARGIN, y - 12, PAGE_WIDTH - MARGIN, y - 12, RULE, 0.75f);
        canvas.text("Overwatch — generated " + STAMP.format(data.generatedAt()),
                MARGIN, y, REGULAR, 8, LABEL);
        canvas.textRight("Page " + page, PAGE_WIDTH - MARGIN, y, REGULAR, 8, LABEL);
    }

    /**
     * The headline figures, three to a row.
     *
     * <p>Tiles rather than a two-column table: these are the numbers somebody
     * reads off a projected slide, and a table of label-value pairs makes the
     * labels as prominent as the figures.
     */
    private static float figures(PdfCanvas canvas, float y, ReportData data) throws IOException {
        Map<String, String> tiles = new LinkedHashMap<>();
        tiles.put("Transactions", count(data.stats().totalTransactions()));
        tiles.put("Alerts raised", count(data.stats().totalAlerts()));
        tiles.put("Still open", count(data.stats().openAlerts()));
        tiles.put("Alert rate", String.format(Locale.ROOT, "%.2f%%", data.alertRatePercent()));
        tiles.put("Mean risk score", String.format(Locale.ROOT, "%.2f", data.stats().averageRiskScore()));
        tiles.put("Flagged (24h)", "R " + count(data.stats().flaggedLast24hZar().longValue()));

        float tileWidth = CONTENT_WIDTH / TILES_PER_ROW;
        int i = 0;
        for (Map.Entry<String, String> tile : tiles.entrySet()) {
            int row = i / TILES_PER_ROW;          // integer division, deliberately
            float x = MARGIN + tileWidth * (i % TILES_PER_ROW);
            float top = y + row * TILE_HEIGHT;
            canvas.text(tile.getKey().toUpperCase(Locale.ROOT), x, top, REGULAR, 8, LABEL);
            canvas.text(tile.getValue(), x, top + 26, BOLD, 20, TEXT);
            i++;
        }
        int rows = (tiles.size() + TILES_PER_ROW - 1) / TILES_PER_ROW;
        return y + TILE_HEIGHT * rows;
    }

    /**
     * Column positions, measured from the left margin.
     *
     * <p>Right edges for the numeric columns, a left edge for the two text ones.
     * Spelled out as one array rather than computed from a width, because the
     * only thing that matters is that no two headers touch, and a table of seven
     * columns is easier to nudge by hand than to parameterise.
     */
    private static final float NAME = MARGIN;
    private static final float STATE = MARGIN + 180;
    private static final float FIRED_RIGHT = MARGIN + 296;
    private static final float SHARE_RIGHT = MARGIN + 350;
    private static final float CONFIRMED_RIGHT = MARGIN + 408;
    private static final float FALSE_POSITIVE_RIGHT = MARGIN + 462;
    private static final float SHADOW_RIGHT = MARGIN + CONTENT_WIDTH;

    private static void ruleTable(PdfCanvas canvas, float y, List<RulePerformance> rules)
            throws IOException {
        canvas.text("Rule", NAME, y, BOLD, 8.5f, LABEL);
        canvas.text("State", STATE, y, BOLD, 8.5f, LABEL);
        canvas.textRight("Fired", FIRED_RIGHT, y, BOLD, 8.5f, LABEL);
        canvas.textRight("Share", SHARE_RIGHT, y, BOLD, 8.5f, LABEL);
        canvas.textRight("Confirmed", CONFIRMED_RIGHT, y, BOLD, 8.5f, LABEL);
        canvas.textRight("False pos.", FALSE_POSITIVE_RIGHT, y, BOLD, 8.5f, LABEL);
        canvas.textRight("Shadow", SHADOW_RIGHT, y, BOLD, 8.5f, LABEL);
        canvas.line(MARGIN, y + 5, PAGE_WIDTH - MARGIN, y + 5, RULE, 0.75f);
        y += 20;

        for (RulePerformance rule : rules) {
            ruleRow(canvas, y, rule);
            canvas.line(MARGIN, y + 6, PAGE_WIDTH - MARGIN, y + 6, RULE, 0.4f);
            y += 21;
        }

        if (rules.isEmpty()) {
            canvas.text("No rules are configured.", MARGIN, y, REGULAR, 9, LABEL);
        }
    }

    private static void ruleRow(PdfCanvas canvas, float y, RulePerformance rule) throws IOException {
        canvas.text(PdfCanvas.ellipsise(rule.name(), STATE - NAME - 10, REGULAR, 9),
                NAME, y, REGULAR, 9, TEXT);
        // A rule that is off is greyed. It still belongs in the table -- "why did
        // nothing fire" is answered by seeing DISABLED, not by the row's absence.
        canvas.text(rule.state(), STATE, y, REGULAR, 9,
                "ENABLED".equals(rule.state()) ? TEXT : LABEL);
        canvas.textRight(count(rule.timesFired()), FIRED_RIGHT, y, REGULAR, 9, TEXT);
        canvas.textRight(String.format(Locale.ROOT, "%.1f%%", rule.shareOfAlerts() * 100),
                SHARE_RIGHT, y, REGULAR, 9, TEXT);
        canvas.textRight(count(rule.confirmed()), CONFIRMED_RIGHT, y, REGULAR, 9, TEXT);
        canvas.textRight(rule.falsePositiveRate() == null ? "—"
                        : String.format(Locale.ROOT, "%.0f%%", rule.falsePositiveRate() * 100),
                FALSE_POSITIVE_RIGHT, y, REGULAR, 9,
                rule.falsePositiveRate() == null ? LABEL : TEXT);
        canvas.textRight(count(rule.shadowHits()), SHADOW_RIGHT, y, REGULAR, 9, LABEL);
    }

    // --------------------------------------------------------------- helpers

    private static List<Long> counts(List<TimeBucket> buckets) {
        return buckets.stream().map(TimeBucket::count).toList();
    }

    /**
     * The ten busiest categories. The generator has a long tail of categories
     * with a handful of transactions each, and twenty one-pixel bars below the
     * ten that matter is noise on a printed page.
     */
    private static Map<String, Long> topCategories(Map<String, Long> all) {
        return all.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    /** Grouped with a plain space; the PDF fonts have no narrow no-break space. */
    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    private static String humanBucket(long seconds) {
        if (seconds % 3600 == 0) {
            long hours = seconds / 3600;
            return hours == 1 ? "hour" : hours + " hours";
        }
        if (seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes == 1 ? "minute" : minutes + " minutes";
        }
        return seconds + " seconds";
    }
}
