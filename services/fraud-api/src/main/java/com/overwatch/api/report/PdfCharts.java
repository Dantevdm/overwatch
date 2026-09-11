package com.overwatch.api.report;

import java.io.IOException;
import java.util.List;

/**
 * The two chart types the report needs, drawn as vectors.
 *
 * <p>No charting library, and no rasterising to PNG first. A report is mostly
 * read on a screen at whatever zoom the reader chose and then printed by someone
 * who wants to read the axis labels, and a 96dpi bitmap of a chart fails both.
 * Two chart types is also the honest size of the requirement — a general charting
 * dependency would bring an AWT surface and a theming API to draw a bar chart
 * with four bars in it.
 */
final class PdfCharts {

    static final int AXIS = 0xCBD5E1;
    static final int GRID = 0xE2E8F0;
    static final int LABEL = 0x64748B;
    static final int TEXT = 0x0F172A;

    /** Gridlines, and therefore axis labels. Enough to read against, few enough to ignore. */
    private static final int GRID_LINES = 4;

    private PdfCharts() {
    }

    /**
     * Horizontal bars with the label inside the axis gutter.
     *
     * <p>Horizontal rather than vertical wherever the categories are words:
     * "GROCERIES" written along a bottom axis has to be rotated or abbreviated,
     * and both are worse than turning the chart on its side.
     */
    static void horizontalBars(PdfCanvas canvas, float x, float y, float width, float height,
                               List<String> labels, List<Long> values, int[] colours)
            throws IOException {
        if (labels.isEmpty()) {
            canvas.text("No data in this window.", x, y + 12, PdfCanvas.REGULAR, 9, LABEL);
            return;
        }
        float gutter = 96;
        long max = Math.max(1, values.stream().mapToLong(Long::longValue).max().orElse(1));
        // The longest value is written past the end of its own bar, so the bar
        // has to stop short of the margin by that much -- otherwise the largest
        // number in the chart, which is the one a reader looks for, runs off the
        // page. Measured rather than guessed: "26 795" and "178 447" differ by a
        // third of their width.
        float valueGutter = PdfCanvas.width(grouped(max), PdfCanvas.REGULAR, 9) + 10;
        float plotX = x + gutter;
        float plotWidth = width - gutter - valueGutter;
        float rowHeight = height / labels.size();
        float barHeight = Math.min(18, rowHeight * 0.6f);

        for (int i = 0; i < labels.size(); i++) {
            float centre = y + rowHeight * i + rowHeight / 2;
            canvas.textRight(PdfCanvas.ellipsise(labels.get(i), gutter - 8, PdfCanvas.REGULAR, 9),
                    plotX - 8, centre + 3, PdfCanvas.REGULAR, 9, LABEL);
            float barWidth = Math.max(plotWidth * values.get(i) / max, 1);
            // rect() takes its top edge, so the bar is raised half its height to
            // sit on the label's centre line rather than under the next one.
            // Zero still gets a sliver, so an empty band reads as a band with
            // nothing in it rather than as a missing row.
            canvas.rect(plotX, centre - barHeight / 2, barWidth, barHeight,
                    colours[i % colours.length]);
            canvas.text(grouped(values.get(i)), plotX + barWidth + 6,
                    centre + 3, PdfCanvas.REGULAR, 9, TEXT);
        }
        canvas.line(plotX, y, plotX, y + height, AXIS, 0.5f);
    }

    /**
     * One or more series against a shared category axis.
     *
     * @param series one entry per line: its name, its colour, and its points.
     */
    static void lines(PdfCanvas canvas, float x, float y, float width, float height,
                      List<String> categories, List<Series> series) throws IOException {
        if (categories.size() < 2) {
            canvas.text("Not enough buckets in this window to draw a line.",
                    x, y + 12, PdfCanvas.REGULAR, 9, LABEL);
            return;
        }
        float gutter = 40;
        float axisHeight = 14;
        float plotX = x + gutter;
        float plotWidth = width - gutter;
        float plotHeight = height - axisHeight;

        long max = 1;
        for (Series s : series) {
            max = Math.max(max, s.values().stream().mapToLong(Long::longValue).max().orElse(0));
        }
        max = roundUp(max);

        for (int i = 0; i <= GRID_LINES; i++) {
            float gy = y + plotHeight - plotHeight * i / GRID_LINES;
            canvas.line(plotX, gy, plotX + plotWidth, gy, i == 0 ? AXIS : GRID, 0.5f);
            canvas.textRight(grouped(max * i / GRID_LINES), plotX - 6, gy + 3,
                    PdfCanvas.REGULAR, 8, LABEL);
        }

        for (Series s : series) {
            plot(canvas, plotX, y, plotWidth, plotHeight, max, s);
        }

        // First and last category only. A label per bucket at forty buckets is a
        // grey smear; the two ends are what actually tell you the window.
        canvas.text(categories.getFirst(), plotX, y + plotHeight + 11, PdfCanvas.REGULAR, 8, LABEL);
        canvas.textRight(categories.getLast(), plotX + plotWidth, y + plotHeight + 11,
                PdfCanvas.REGULAR, 8, LABEL);
    }

    private static void plot(PdfCanvas canvas, float plotX, float y, float plotWidth,
                             float plotHeight, long max, Series series) throws IOException {
        int n = series.values().size();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = plotX + (n == 1 ? 0 : plotWidth * i / (n - 1));
            ys[i] = y + plotHeight - plotHeight * series.values().get(i) / max;
        }
        canvas.path(xs, ys, series.colour(), 1.2f);
    }

    /** A legend swatch and label, laid out left to right, returning the next x. */
    static float legend(PdfCanvas canvas, float x, float y, String name, int colour)
            throws IOException {
        // Raised onto the text's midline. rect() takes a top edge and text() a
        // baseline, so a swatch drawn at the same y sits a full square low.
        canvas.rect(x, y - 7, 8, 8, colour);
        canvas.text(name, x + 12, y, PdfCanvas.REGULAR, 9, LABEL);
        return x + 12 + PdfCanvas.width(name, PdfCanvas.REGULAR, 9) + 18;
    }

    /**
     * Round an axis maximum up to something a person would have chosen: 1, 2 or 5
     * times a power of ten. An axis topping out at 4 173 makes every gridline
     * label a number nobody can divide by eye.
     */
    private static long roundUp(long max) {
        long magnitude = 1;
        while (magnitude * 10 <= max) {
            magnitude *= 10;
        }
        for (long step : new long[]{1, 2, 5, 10}) {
            if (max <= magnitude * step) {
                return magnitude * step;
            }
        }
        return magnitude * 10;
    }

    /** Thousands separated, matching the tiles above the chart and the UI. */
    private static String grouped(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    record Series(String name, int colour, List<Long> values) {
    }
}
