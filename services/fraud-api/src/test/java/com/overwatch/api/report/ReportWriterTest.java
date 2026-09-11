package com.overwatch.api.report;

import com.overwatch.api.dto.Dtos.DashboardStats;
import com.overwatch.api.dto.Dtos.RulePerformance;
import com.overwatch.api.dto.Dtos.SeverityBucket;
import com.overwatch.api.dto.Dtos.TimeBucket;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What these assert is that the files open, and that the figures in them are the
 * figures they were given. Pixel placement is not testable here and is not what
 * breaks — what breaks is a series pointed at the wrong column, or a total that
 * disagrees with the dashboard it was exported from.
 */
class ReportWriterTest {

    private static ReportData sample(int buckets, int rules) {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");
        Map<String, Long> severity = new LinkedHashMap<>(Map.of());
        severity.put("LOW", 40L);
        severity.put("MEDIUM", 25L);
        severity.put("HIGH", 12L);
        severity.put("CRITICAL", 3L);

        Map<String, Long> categories = new LinkedHashMap<>();
        categories.put("GROCERIES", 900L);
        categories.put("FUEL", 400L);

        List<TimeBucket> alerts = new ArrayList<>();
        List<TimeBucket> volume = new ArrayList<>();
        List<SeverityBucket> mix = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            Instant bucket = now.minus(buckets - i, ChronoUnit.HOURS);
            alerts.add(new TimeBucket(bucket, i));
            volume.add(new TimeBucket(bucket, i * 50L));
            mix.add(new SeverityBucket(bucket, Map.of("LOW", (long) i, "MEDIUM", 1L,
                    "HIGH", 0L, "CRITICAL", 0L)));
        }

        List<RulePerformance> performance = new ArrayList<>();
        for (int i = 0; i < rules; i++) {
            performance.add(new RulePerformance((long) i, "RULE_" + i, "Rule number " + i,
                    i % 2 == 0 ? "ACTIVE" : "SHADOW", new BigDecimal("0.35"),
                    10L + i, 0.1, 4L, 2L, i == 0 ? null : 0.25, 7L));
        }

        return new ReportData(now, 24 * 60,
                new DashboardStats(1300, 120, 80, 31, 0.62,
                        new BigDecimal("412500.50"), severity, categories,
                        24 * 60, 3600, alerts, volume, mix),
                performance);
    }

    @Test
    void theWorkbookHasASheetPerQuestion() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(ExcelReport.write(sample(24, 6))))) {
            assertThat(sheetNames(workbook)).containsExactly(
                    "Summary", "Activity", "Severity over time",
                    "Merchant categories", "Rule performance");
        }
    }

    @Test
    void everySheetWithAChartCarriesARealChart() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(ExcelReport.write(sample(24, 6))))) {
            // Not a picture of a chart. If these ever come back empty the workbook
            // still opens and still looks fine at a glance, which is exactly the
            // regression worth catching.
            for (String name : List.of("Summary", "Activity", "Severity over time",
                    "Merchant categories")) {
                assertThat(workbook.getSheet(name).createDrawingPatriarch().getCharts())
                        .as("charts on %s", name)
                        .isNotEmpty();
            }
        }
    }

    @Test
    void theActivitySheetHasOneRowPerBucket() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(ExcelReport.write(sample(24, 6))))) {
            Sheet sheet = workbook.getSheet("Activity");
            // Row 3 is the header; the data starts at 4 and there are 24 buckets.
            assertThat(sheet.getLastRowNum()).isEqualTo(3 + 24);
            assertThat(sheet.getRow(4).getCell(2).getNumericCellValue()).isZero();
            assertThat(sheet.getRow(4 + 23).getCell(2).getNumericCellValue()).isEqualTo(23 * 50);
        }
    }

    @Test
    void figuresAreNumbersRatherThanFormattedText() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(ExcelReport.write(sample(24, 6))))) {
            // Found by its label rather than by row index, so adding a figure
            // above it does not break the assertion about this one.
            Cell flagged = valueBesideLabel(workbook.getSheet("Summary"),
                    "Value flagged in the last 24 hours (ZAR)");
            // "R 412 500.50" in a cell is a string nobody can sum. The formatting
            // belongs to the cell style, and the value stays a number.
            assertThat(flagged.getNumericCellValue()).isEqualTo(412500.50);
            assertThat(flagged.getCellStyle().getDataFormatString()).contains("R");
        }
    }

    @Test
    void thePdfSaysWhatItCovers() throws IOException {
        String text = pdfText(PdfReport.write(sample(24, 6)));
        assertThat(text).contains("Fraud detection report");
        assertThat(text).contains("the last 24 hours");
        assertThat(text).contains("Is every rule earning its place?");
        assertThat(text).contains("Rule number 3");
    }

    @Test
    void thePdfCarriesTheFiguresItWasGiven() throws IOException {
        String text = pdfText(PdfReport.write(sample(24, 6)));
        assertThat(text).contains("1 300");        // transactions
        assertThat(text).contains("6.15%");        // 80 alerts of 1300
    }

    @Test
    void aLongRuleListSpillsOntoFurtherPages() throws IOException {
        try (PDDocument document = Loader.loadPDF(PdfReport.write(sample(24, 40)))) {
            // Overview, activity, and two pages of rules at 28 to a page.
            assertThat(document.getNumberOfPages()).isEqualTo(4);
        }
    }

    @Test
    void anEmptyWindowStillProducesBothFiles() throws IOException {
        ReportData empty = new ReportData(Instant.parse("2026-01-15T12:00:00Z"), 60,
                new DashboardStats(0, 0, 0, 0, 0.0, BigDecimal.ZERO,
                        new LinkedHashMap<>(), new LinkedHashMap<>(), 60, 60,
                        List.of(), List.of(), List.of()),
                List.of());

        // A report of nothing is the first thing a reader generates, before the
        // simulator has been started. It must say "nothing" rather than throw.
        assertThat(ExcelReport.write(empty)).isNotEmpty();
        assertThat(pdfText(PdfReport.write(empty))).contains("No data in this window.");
    }

    @Test
    void charactersTheStandardFontsCannotShowAreMappedRatherThanDropped() {
        assertThat(PdfCanvas.safe("an — dash and a   space")).isEqualTo("an - dash and a   space");
        assertThat(PdfCanvas.safe("中")).isEqualTo("?");
    }

    /** The cell in column B of the row whose column A reads {@code label}. */
    private static Cell valueBesideLabel(Sheet sheet, String label) {
        for (Row row : sheet) {
            Cell first = row.getCell(0);
            if (first != null && label.equals(first.getStringCellValue())) {
                return row.getCell(1);
            }
        }
        throw new AssertionError("No row labelled " + label);
    }

    private static List<String> sheetNames(XSSFWorkbook workbook) {
        List<String> names = new ArrayList<>();
        workbook.sheetIterator().forEachRemaining(s -> names.add(s.getSheetName()));
        return names;
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
