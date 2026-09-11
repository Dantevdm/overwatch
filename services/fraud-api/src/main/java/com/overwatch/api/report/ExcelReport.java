package com.overwatch.api.report;

import com.overwatch.api.dto.Dtos.RulePerformance;
import com.overwatch.api.dto.Dtos.SeverityBucket;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.PresetColor;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFLineProperties;
import org.apache.poi.xddf.usermodel.XDDFShapeProperties;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The report as a workbook.
 *
 * <p>A workbook, not a PDF-in-a-spreadsheet. Someone opening this wants to sort
 * the rule table, filter to CRITICAL, and paste a column into a model — so every
 * sheet is a real table with a header row and one record per row, numbers are
 * numbers rather than pre-formatted strings, and the charts are native Excel
 * charts bound to those cells. Change a cell and the chart moves; that is the
 * whole reason to hand someone a workbook instead of a picture.
 */
final class ExcelReport {

    /** Matches the severity colours the dashboard and Grafana already use. */
    private static final Map<String, byte[]> SEVERITY_COLOURS = Map.of(
            "LOW", new byte[]{(byte) 0x3B, (byte) 0x82, (byte) 0xF6},
            "MEDIUM", new byte[]{(byte) 0xF5, (byte) 0x9E, (byte) 0x0B},
            "HIGH", new byte[]{(byte) 0xEF, (byte) 0x6C, (byte) 0x00},
            "CRITICAL", new byte[]{(byte) 0xDC, (byte) 0x26, (byte) 0x26});

    private static final DateTimeFormatter BUCKET_LABEL =
            DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault());

    private ExcelReport() {
    }

    static byte[] write(ReportData data) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            summarySheet(workbook, styles, data);
            activitySheet(workbook, styles, data);
            severitySheet(workbook, styles, data);
            categorySheet(workbook, styles, data);
            ruleSheet(workbook, styles, data);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- sheets

    private static void summarySheet(XSSFWorkbook workbook, Styles styles, ReportData data) {
        XSSFSheet sheet = workbook.createSheet("Summary");
        int r = 0;
        title(sheet, styles, r++, "Overwatch — fraud detection report");
        text(sheet, styles.muted, r++, 0, "Covering " + data.windowLabel()
                + ", generated " + BUCKET_LABEL.format(data.generatedAt()));
        r++;

        heading(sheet, styles, r++, "Headline figures");
        r = figure(sheet, styles, r, "Transactions processed", data.stats().totalTransactions(), styles.count);
        r = figure(sheet, styles, r, "Transactions in the last hour", data.stats().transactionsLastHour(), styles.count);
        r = figure(sheet, styles, r, "Alerts raised", data.stats().totalAlerts(), styles.count);
        r = figure(sheet, styles, r, "Alerts still open", data.stats().openAlerts(), styles.count);
        r = figure(sheet, styles, r, "Alert rate", data.alertRatePercent() / 100, styles.percent);
        r = figure(sheet, styles, r, "Mean risk score of an alert", data.stats().averageRiskScore(), styles.decimal);
        r = figure(sheet, styles, r, "Value flagged in the last 24 hours (ZAR)",
                data.stats().flaggedLast24hZar().doubleValue(), styles.money);
        r++;

        heading(sheet, styles, r++, "Alerts by severity");
        int severityHeader = r;
        row(sheet, styles.header, r++, "Severity", "Alerts");
        int firstSeverity = r;
        for (Map.Entry<String, Long> e : data.stats().alertsBySeverity().entrySet()) {
            text(sheet, styles.body, r, 0, e.getKey());
            number(sheet, styles.count, r, 1, e.getValue());
            r++;
        }

        // Guarded, here and on every other sheet: a chart over an empty range is
        // not an empty chart, it is a cell range whose last row precedes its
        // first, and POI rejects it. A report generated before the simulator has
        // been started is the first one anybody runs.
        if (r > firstSeverity) {
            // Doughnut rather than a bar: severity is a composition of one total,
            // and the question asked of it is "how much of tonight is critical",
            // not "which band is tallest".
            XSSFChart chart = chartAt(sheet, 3, severityHeader, 10, severityHeader + 16,
                    "Share of alerts by severity");
            XDDFChartData pie = chart.createData(ChartTypes.DOUGHNUT, null, null);
            pie.setVaryColors(true);
            XDDFChartData.Series slices = pie.addSeries(
                    strings(sheet, firstSeverity, r - 1, 0), numbers(sheet, firstSeverity, r - 1, 1));
            slices.setTitle("Alerts", null);
            chart.plot(pie);
            legendAt(chart, LegendPosition.RIGHT);
        }

        sheet.setColumnWidth(0, 42 * 256);
        sheet.setColumnWidth(1, 16 * 256);
    }

    private static void activitySheet(XSSFWorkbook workbook, Styles styles, ReportData data) {
        XSSFSheet sheet = workbook.createSheet("Activity");
        int r = 0;
        title(sheet, styles, r++, "Alerts against transaction volume");
        text(sheet, styles.muted, r++, 0,
                "One row per " + humanBucket(data.stats().bucketSeconds())
                        + ". Alerts alone cannot tell a quiet night from a broken "
                        + "detector — the volume column is what separates them.");
        r++;

        int header = r;
        row(sheet, styles.header, r++, "Bucket", "Alerts", "Transactions");
        int first = r;
        List<com.overwatch.api.dto.Dtos.TimeBucket> alerts = data.stats().alertsOverTime();
        List<com.overwatch.api.dto.Dtos.TimeBucket> volume = data.stats().transactionsOverTime();
        for (int i = 0; i < alerts.size(); i++) {
            text(sheet, styles.body, r, 0, BUCKET_LABEL.format(alerts.get(i).bucket()));
            number(sheet, styles.count, r, 1, alerts.get(i).count());
            number(sheet, styles.count, r, 2, i < volume.size() ? volume.get(i).count() : 0);
            r++;
        }

        if (r > first) {
            XSSFChart chart = chartAt(sheet, 4, header, 16, header + 22,
                    "Alerts and transactions over time");
            XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
            left.setCrosses(AxisCrosses.AUTO_ZERO);
            XDDFChartData line = chart.createData(ChartTypes.LINE, bottom, left);
            line.setVaryColors(false);
            addLine(line, strings(sheet, first, r - 1, 0), numbers(sheet, first, r - 1, 1),
                    "Alerts", new byte[]{(byte) 0xDC, (byte) 0x26, (byte) 0x26});
            addLine(line, strings(sheet, first, r - 1, 0), numbers(sheet, first, r - 1, 2),
                    "Transactions", new byte[]{(byte) 0x64, (byte) 0x74, (byte) 0x8B});
            chart.plot(line);
            legendAt(chart, LegendPosition.BOTTOM);
        }

        sheet.setColumnWidth(0, 20 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 16 * 256);
    }

    private static void severitySheet(XSSFWorkbook workbook, Styles styles, ReportData data) {
        XSSFSheet sheet = workbook.createSheet("Severity over time");
        List<String> bands = List.copyOf(data.stats().alertsBySeverity().keySet());
        int r = 0;
        title(sheet, styles, r++, "Severity mix over time");
        text(sheet, styles.muted, r++, 0,
                "Stacked, because the interesting movement is the mix rather than the total.");
        r++;

        int header = r;
        Object[] headerRow = new Object[bands.size() + 1];
        headerRow[0] = "Bucket";
        for (int i = 0; i < bands.size(); i++) {
            headerRow[i + 1] = bands.get(i);
        }
        row(sheet, styles.header, r++, headerRow);
        int first = r;
        for (SeverityBucket bucket : data.stats().severityOverTime()) {
            text(sheet, styles.body, r, 0, BUCKET_LABEL.format(bucket.bucket()));
            for (int i = 0; i < bands.size(); i++) {
                number(sheet, styles.count, r, i + 1, bucket.counts().getOrDefault(bands.get(i), 0L));
            }
            r++;
        }

        if (r > first) {
            XSSFChart chart = chartAt(sheet, bands.size() + 2, header, bands.size() + 14, header + 22,
                    "Alerts by severity over time");
            XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
            left.setCrosses(AxisCrosses.AUTO_ZERO);
            // Stacked area rather than stacked columns. Two reasons, one of them
            // aesthetic: time is continuous, so an area reads as a mix changing
            // rather than forty separate readings. The other is that stacked
            // columns need an <overlap> element whose schema type is absent from
            // poi-ooxml-lite, and pulling the full 15MB schema jar to set one
            // byte would be a strange trade for a chart that is better as an area.
            XDDFAreaChartData area = (XDDFAreaChartData) chart.createData(ChartTypes.AREA, bottom, left);
            area.setGrouping(Grouping.STACKED);
            for (int i = 0; i < bands.size(); i++) {
                XDDFChartData.Series s = area.addSeries(
                        strings(sheet, first, r - 1, 0), numbers(sheet, first, r - 1, i + 1));
                s.setTitle(bands.get(i), null);
                fill(s, SEVERITY_COLOURS.getOrDefault(bands.get(i), new byte[]{0x64, 0x74, (byte) 0x8B}));
            }
            chart.plot(area);
            legendAt(chart, LegendPosition.BOTTOM);
        }

        sheet.setColumnWidth(0, 20 * 256);
    }

    private static void categorySheet(XSSFWorkbook workbook, Styles styles, ReportData data) {
        XSSFSheet sheet = workbook.createSheet("Merchant categories");
        int r = 0;
        title(sheet, styles, r++, "Transactions by merchant category");
        r++;

        int header = r;
        row(sheet, styles.header, r++, "Category", "Transactions");
        int first = r;
        for (Map.Entry<String, Long> e : data.stats().transactionsByCategory().entrySet()) {
            text(sheet, styles.body, r, 0, e.getKey());
            number(sheet, styles.count, r, 1, e.getValue());
            r++;
        }

        if (r > first) {
            XSSFChart chart = chartAt(sheet, 3, header, 12, header + 18, "Transactions by category");
            XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
            XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
            left.setCrosses(AxisCrosses.AUTO_ZERO);
            XDDFBarChartData bars = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottom, left);
            // Horizontal bars: category names are words, and words fit along a
            // left axis without being rotated forty-five degrees to fit.
            bars.setBarDirection(BarDirection.BAR);
            XDDFChartData.Series s = bars.addSeries(
                    strings(sheet, first, r - 1, 0), numbers(sheet, first, r - 1, 1));
            s.setTitle("Transactions", null);
            fill(s, new byte[]{(byte) 0x00, (byte) 0x9D, (byte) 0xE0});
            chart.plot(bars);
            chart.setTitleOverlay(false);
        }

        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 16 * 256);
    }

    private static void ruleSheet(XSSFWorkbook workbook, Styles styles, ReportData data) {
        XSSFSheet sheet = workbook.createSheet("Rule performance");
        int r = 0;
        title(sheet, styles, r++, "Is every rule earning its place?");
        text(sheet, styles.muted, r++, 0,
                "A blank false-positive rate means nothing has been reviewed yet. "
                        + "Zero would read as a perfect rule, which is a different claim.");
        r++;

        int header = r;
        row(sheet, styles.header, r++, "Rule", "Type", "State", "Weight", "Times fired",
                "Share of alerts", "Confirmed", "Cleared", "False positive rate", "Shadow hits");
        for (RulePerformance rule : data.rules()) {
            int c = 0;
            text(sheet, styles.body, r, c++, rule.name());
            text(sheet, styles.body, r, c++, rule.ruleType());
            text(sheet, styles.body, r, c++, rule.state());
            number(sheet, styles.decimal, r, c++, rule.weight().doubleValue());
            number(sheet, styles.count, r, c++, rule.timesFired());
            number(sheet, styles.percent, r, c++, rule.shareOfAlerts());
            number(sheet, styles.count, r, c++, rule.confirmed());
            number(sheet, styles.count, r, c++, rule.cleared());
            if (rule.falsePositiveRate() != null) {
                number(sheet, styles.percent, r, c, rule.falsePositiveRate());
            }
            c++;
            number(sheet, styles.count, r, c, rule.shadowHits());
            r++;
        }

        // An autofilter and a frozen header, because this is the sheet people
        // actually work in rather than read.
        if (r > header + 1) {
            sheet.setAutoFilter(new CellRangeAddress(header, r - 1, 0, 9));
        }
        sheet.createFreezePane(0, header + 1);
        for (int c = 0; c <= 9; c++) {
            sheet.setColumnWidth(c, (c == 0 ? 34 : 17) * 256);
        }
    }

    // ---------------------------------------------------------------- helpers

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

    private static XSSFChart chartAt(XSSFSheet sheet, int col1, int row1, int col2, int row2,
                                     String titleText) {
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFChart chart = drawing.createChart(
                drawing.createAnchor(0, 0, 0, 0, col1, row1, col2, row2));
        chart.setTitleText(titleText);
        chart.setTitleOverlay(false);
        return chart;
    }

    private static void legendAt(XSSFChart chart, LegendPosition position) {
        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(position);
    }

    private static void addLine(XDDFChartData data, XDDFDataSource<String> categories,
                                XDDFNumericalDataSource<Double> values, String name, byte[] rgb) {
        XDDFLineChartData.Series series = (XDDFLineChartData.Series) data.addSeries(categories, values);
        series.setTitle(name, null);
        series.setSmooth(false);              // a spline through counts invents values between buckets
        series.setMarkerStyle(MarkerStyle.NONE);
        XDDFShapeProperties properties = new XDDFShapeProperties();
        properties.setLineProperties(new XDDFLineProperties(
                new XDDFSolidFillProperties(XDDFColor.from(rgb))));
        series.setShapeProperties(properties);
    }

    private static void fill(XDDFChartData.Series series, byte[] rgb) {
        XDDFShapeProperties properties = new XDDFShapeProperties();
        properties.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(rgb)));
        series.setShapeProperties(properties);
    }

    private static XDDFDataSource<String> strings(XSSFSheet sheet, int firstRow, int lastRow, int column) {
        return XDDFDataSourcesFactory.fromStringCellRange(sheet,
                new CellRangeAddress(firstRow, lastRow, column, column));
    }

    private static XDDFNumericalDataSource<Double> numbers(XSSFSheet sheet, int firstRow, int lastRow,
                                                           int column) {
        return XDDFDataSourcesFactory.fromNumericCellRange(sheet,
                new CellRangeAddress(firstRow, lastRow, column, column));
    }

    private static void title(XSSFSheet sheet, Styles styles, int rowIndex, String value) {
        text(sheet, styles.title, rowIndex, 0, value);
    }

    private static void heading(XSSFSheet sheet, Styles styles, int rowIndex, String value) {
        text(sheet, styles.heading, rowIndex, 0, value);
    }

    private static int figure(XSSFSheet sheet, Styles styles, int rowIndex, String label,
                              double value, CellStyle style) {
        text(sheet, styles.body, rowIndex, 0, label);
        number(sheet, style, rowIndex, 1, value);
        return rowIndex + 1;
    }

    private static void row(XSSFSheet sheet, CellStyle style, int rowIndex, Object... values) {
        for (int c = 0; c < values.length; c++) {
            text(sheet, style, rowIndex, c, String.valueOf(values[c]));
        }
    }

    private static void text(XSSFSheet sheet, CellStyle style, int rowIndex, int column, String value) {
        cell(sheet, rowIndex, column, style).setCellValue(value);
    }

    private static void number(XSSFSheet sheet, CellStyle style, int rowIndex, int column, double value) {
        cell(sheet, rowIndex, column, style).setCellValue(value);
    }

    private static Cell cell(XSSFSheet sheet, int rowIndex, int column, CellStyle style) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        Cell cell = row.getCell(column);
        if (cell == null) {
            cell = row.createCell(column);
        }
        cell.setCellStyle(style);
        return cell;
    }

    /**
     * Cell styles, created once per workbook.
     *
     * <p>POI caps a workbook at 64 000 styles and creating one per cell reaches
     * that on a sheet this size, so they are built up front and shared.
     */
    private static final class Styles {
        private final CellStyle title;
        private final CellStyle heading;
        private final CellStyle header;
        private final CellStyle body;
        private final CellStyle muted;
        private final CellStyle count;
        private final CellStyle decimal;
        private final CellStyle percent;
        private final CellStyle money;

        private Styles(XSSFWorkbook workbook) {
            DataFormat formats = workbook.createDataFormat();
            this.title = withFont(workbook, 16, true, null);
            this.heading = withFont(workbook, 12, true, null);
            this.body = withFont(workbook, 11, false, null);
            this.muted = withFont(workbook, 10, false, IndexedColors.GREY_50_PERCENT);

            CellStyle headerStyle = withFont(workbook, 11, true, null);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            this.header = headerStyle;

            this.count = numeric(workbook, formats, "#,##0");
            this.decimal = numeric(workbook, formats, "#,##0.00");
            this.percent = numeric(workbook, formats, "0.0%");
            // ZAR with a space as the thousands separator, matching the UI and
            // the CLI. Excel writes the separator the reader's locale asks for
            // unless the format string says otherwise.
            this.money = numeric(workbook, formats, "\"R\"\\ #,##0.00");
        }

        private static CellStyle withFont(XSSFWorkbook workbook, int points, boolean bold,
                                          IndexedColors colour) {
            Font font = workbook.createFont();
            font.setFontHeightInPoints((short) points);
            font.setBold(bold);
            if (colour != null) {
                font.setColor(colour.getIndex());
            }
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            return style;
        }

        private static CellStyle numeric(XSSFWorkbook workbook, DataFormat formats, String pattern) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(formats.getFormat(pattern));
            return style;
        }
    }
}
