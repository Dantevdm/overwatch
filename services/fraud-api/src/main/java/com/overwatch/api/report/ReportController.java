package com.overwatch.api.report;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Reports as files.
 *
 * <p>Generated on the server rather than in the browser. The same document is
 * then reachable from the dashboard, from `curl`, from Postman and from a
 * scheduler, and the numbers in it come from the same service the dashboard
 * reads — a client-side exporter would be a second implementation of every
 * aggregate, drifting quietly from the first.
 */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports", description = "The dashboard as a document you can send to someone")
public class ReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /** Sortable, and safe on every filesystem — no colons, no spaces. */
    private static final DateTimeFormatter FILENAME_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneId.systemDefault());

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping(value = "/fraud-summary.xlsx", produces =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(summary = "The report as a workbook",
            description = """
                    Five sheets — summary, activity, severity over time, merchant
                    categories and rule performance — with native Excel charts bound
                    to the cells rather than pictures of charts, so a reader can
                    re-sort a table or retarget a series and the chart follows.

                    `rangeMinutes` sets the window the time series cover, clamped the
                    same way the dashboard clamps it.""")
    public ResponseEntity<byte[]> workbook(
            @RequestParam(required = false)
            @Parameter(description = "Window for the time series, in minutes. "
                    + "Defaults to 1440 (24h); capped at 10080 (7 days).")
            Integer rangeMinutes) {
        ReportData data = reports.snapshot(rangeMinutes);
        return file(bytes(() -> ExcelReport.write(data)), XLSX, "xlsx", data);
    }

    @GetMapping(value = "/fraud-summary.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "The report as a document",
            description = """
                    Three sections: the headline figures and where the alerts landed,
                    how the window moved, and whether each rule is earning its place.
                    Charts are drawn as vectors, so the axis labels survive being
                    printed or zoomed.""")
    public ResponseEntity<byte[]> document(
            @RequestParam(required = false)
            @Parameter(description = "Window for the time series, in minutes. "
                    + "Defaults to 1440 (24h); capped at 10080 (7 days).")
            Integer rangeMinutes) {
        ReportData data = reports.snapshot(rangeMinutes);
        return file(bytes(() -> PdfReport.write(data)), MediaType.APPLICATION_PDF, "pdf", data);
    }

    /**
     * Both writers stream into a buffer and hand back bytes, so a failure part of
     * the way through produces a 500 rather than a truncated file the browser has
     * already started saving.
     */
    private static byte[] bytes(Writer writer) {
        try {
            return writer.write();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not generate the report", e);
        }
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String extension,
                                               ReportData data) {
        String name = "overwatch-fraud-report-" + FILENAME_STAMP.format(data.generatedAt())
                + "." + extension;
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .body(body);
    }

    @FunctionalInterface
    private interface Writer {
        byte[] write() throws IOException;
    }
}
