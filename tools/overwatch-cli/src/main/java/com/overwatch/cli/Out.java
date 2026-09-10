package com.overwatch.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the CLI prints, and the formatting rules behind it.
 *
 * <p>Kept in one place because consistency across a dozen subcommands is the
 * whole difference between a tool and a pile of scripts: an amount is formatted
 * the same way on every screen, a severity is the same colour everywhere, and a
 * table's rules line up whichever command drew it.
 */
public final class Out {

    /**
     * SAST. Every timestamp the API returns is UTC and every person reading
     * this output is looking at a South African card stream, so rendering in
     * UTC would mean mentally adding two hours to every late-night alert — in a
     * product where "late night" is a rule.
     */
    private static final ZoneId ZONE = ZoneId.of("Africa/Johannesburg");

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd MMM HH:mm:ss").withZone(ZONE);

    private Out() { }

    public static void line(String s) {
        System.out.println(s);
    }

    public static void line() {
        System.out.println();
    }

    /** A section heading: bold, with a rule under it the width of the text. */
    public static void heading(String s) {
        line();
        line(Ansi.bold(s));
        line(Ansi.dim("─".repeat(Math.max(4, Ansi.width(s)))));
    }

    public static void note(String s) {
        line(Ansi.dim(s));
    }

    public static void ok(String s) {
        line(Ansi.green("✓ ") + s);
    }

    public static void warn(String s) {
        line(Ansi.yellow("! ") + s);
    }

    public static void fail(String s) {
        line(Ansi.red("✗ ") + s);
    }

    // ---- money, scores, time ----------------------------------------------

    /**
     * Rand, grouped, two decimals: {@code R107 658.95}.
     *
     * <p>A non-breaking space as the group separator, matching the UI, because a
     * wrapped amount in a table cell is a number that reads as two numbers.
     *
     * <p>Formatted in {@link Locale#ROOT} and then substituted, NOT in en-ZA.
     * That looks like the wrong way round and is not: en-ZA uses a comma as the
     * DECIMAL separator and a space for grouping, so formatting in en-ZA and
     * replacing every comma with a space eats the decimal point. R107 658,95
     * came out as "R107 658 95" -- not a wrong-looking number, a plausible one
     * that is wrong by a factor of a hundred. Locale.ROOT puts a comma only
     * where a group separator belongs, so the substitution has exactly one
     * meaning. It also makes the output identical on a laptop set to en-US or
     * de-DE, which comparing two runs depends on.
     */
    public static String zar(double amount) {
        return "R" + String.format(Locale.ROOT, "%,.2f", amount).replace(',', '\u00A0');
    }

    /** Whole numbers, grouped the same way and for the same reason. */
    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n).replace(',', '\u00A0');
    }

    public static String score(double s) {
        return String.format(Locale.ROOT, "%.2f", s);
    }

    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }

    public static String time(String isoInstant) {
        Instant when = parse(isoInstant);
        return when == null ? "—" : TIME.format(when);
    }

    public static String dateTime(String isoInstant) {
        Instant when = parse(isoInstant);
        return when == null ? "—" : DATE_TIME.format(when);
    }

    /** "4m ago" — the form that answers "is this current" without arithmetic. */
    public static String ago(String isoInstant) {
        Instant when = parse(isoInstant);
        if (when == null) return "—";
        Duration since = Duration.between(when, Instant.now());
        if (since.isNegative()) return "just now";
        long seconds = since.toSeconds();
        if (seconds < 10) return "just now";
        if (seconds < 90) return seconds + "s ago";
        long minutes = since.toMinutes();
        if (minutes < 90) return minutes + "m ago";
        long hours = since.toHours();
        if (hours < 36) return hours + "h ago";
        return since.toDays() + "d ago";
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- severity ----------------------------------------------------------

    /**
     * Severity, coloured and padded to a fixed width.
     *
     * <p>The label is always present. Colour is an accelerator, never the
     * carrier: this output goes into pipes, logs and terminals with a red-green
     * palette someone cannot distinguish, and "the red ones" is not a
     * specification.
     */
    public static String severity(String name) {
        String padded = Ansi.pad(name == null ? "—" : name, 8);
        if (name == null) return Ansi.dim(padded);
        return switch (name) {
            case "CRITICAL" -> Ansi.brightRed(Ansi.bold(padded));
            case "HIGH" -> Ansi.red(padded);
            case "MEDIUM" -> Ansi.yellow(padded);
            case "LOW" -> Ansi.dim(padded);
            default -> padded;
        };
    }

    public static String status(String name) {
        if (name == null) return "—";
        return switch (name) {
            case "OPEN" -> Ansi.yellow(name);
            case "CONFIRMED_FRAUD" -> Ansi.red(name);
            case "FALSE_POSITIVE" -> Ansi.green(name);
            default -> Ansi.dim(name);
        };
    }

    public static String ruleState(String state) {
        if (state == null) return "—";
        return switch (state) {
            case "ENABLED" -> Ansi.green(state);
            case "SHADOW" -> Ansi.cyan(state);
            case "DISABLED" -> Ansi.dim(state);
            default -> state;
        };
    }

    // ---- tables ------------------------------------------------------------

    /** How a column's values are aligned in it. */
    public enum Align { LEFT, RIGHT }

    /**
     * A table that measures its own columns.
     *
     * <p>Widths come from the content, using {@link Ansi#width} so a coloured
     * cell is measured by what it shows rather than by how many bytes it
     * occupies. That one detail is the difference between a table and a mess:
     * every hand-rolled ANSI table that looks ragged is ragged only in the
     * columns that happen to be coloured.
     */
    public static final class Table {

        private final List<String> headers = new ArrayList<>();
        private final List<Align> aligns = new ArrayList<>();
        private final List<List<String>> rows = new ArrayList<>();

        public Table column(String header) {
            return column(header, Align.LEFT);
        }

        public Table column(String header, Align align) {
            headers.add(header);
            aligns.add(align);
            return this;
        }

        public Table row(String... cells) {
            rows.add(List.of(cells));
            return this;
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        public void print() {
            if (headers.isEmpty()) return;

            int[] widths = measure();
            printHeader(widths);
            for (List<String> row : rows) {
                // Stripped, so a right-hand column padded to width does not
                // leave trailing spaces that show up when the output is
                // diffed or pasted.
                Out.line(layout(row, widths).stripTrailing());
            }
        }

        /** The widest visible cell in each column, header included. */
        private int[] measure() {
            int[] widths = new int[headers.size()];
            for (int i = 0; i < headers.size(); i++) {
                widths[i] = Ansi.width(headers.get(i));
            }
            for (List<String> row : rows) {
                for (int i = 0; i < row.size() && i < widths.length; i++) {
                    widths[i] = Math.max(widths[i], Ansi.width(row.get(i)));
                }
            }
            return widths;
        }

        private void printHeader(int[] widths) {
            List<String> labels = new ArrayList<>();
            StringBuilder rule = new StringBuilder();
            for (int i = 0; i < headers.size(); i++) {
                labels.add(headers.get(i).toUpperCase(Locale.ROOT));
                if (i > 0) rule.append("  ");
                rule.append("─".repeat(widths[i]));
            }
            Out.line(Ansi.dim(layout(labels, widths)));
            Out.line(Ansi.dim(rule.toString()));
        }

        /** One row, padded per column and joined by the gutter. */
        private String layout(List<String> cells, int[] widths) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) out.append("  ");
                String cell = i < cells.size() ? cells.get(i) : "";
                out.append(aligns.get(i) == Align.RIGHT
                        ? Ansi.padLeft(cell, widths[i])
                        : Ansi.pad(cell, widths[i]));
            }
            return out.toString();
        }
    }

    /**
     * A horizontal bar, for a proportion.
     *
     * <p>Block characters rather than {@code #}, because at eight-eighths
     * resolution a bar can show a difference of one percent in twenty
     * characters, and a bar that cannot show the difference between two rules
     * is decoration.
     */
    public static String bar(double fraction, int width) {
        double clamped = Math.max(0, Math.min(1, fraction));
        int eighths = (int) Math.round(clamped * width * 8);
        int full = eighths / 8;
        int remainder = eighths % 8;
        StringBuilder out = new StringBuilder("█".repeat(Math.min(full, width)));
        if (full < width && remainder > 0) {
            out.append("▏▎▍▌▋▊▉".charAt(Math.min(remainder - 1, 6)));
        }
        return Ansi.pad(out.toString(), width);
    }

    /**
     * A sparkline over a series, in one character per point.
     *
     * <p>Scaled to the series' own maximum with a zero floor, so the shape is
     * the shape of the traffic. Scaling to min-max instead would turn a flat
     * series into dramatic noise, which is the usual bug in terminal
     * sparklines.
     */
    public static String sparkline(List<Long> values) {
        if (values == null || values.isEmpty()) return "";
        final String ramp = "▁▂▃▄▅▆▇█";
        long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
        if (max <= 0) return ramp.substring(0, 1).repeat(values.size());
        StringBuilder out = new StringBuilder();
        for (long value : values) {
            int index = (int) Math.round((double) value / max * (ramp.length() - 1));
            out.append(ramp.charAt(Math.max(0, Math.min(ramp.length() - 1, index))));
        }
        return out.toString();
    }

    /** Shorten an id to its first segment — enough to identify, short enough to scan. */
    public static String shortId(String id) {
        if (id == null) return "—";
        int dash = id.indexOf('-');
        return dash > 0 ? id.substring(0, dash) : (id.length() > 8 ? id.substring(0, 8) : id);
    }

    /** Truncate to a width, with an ellipsis, so one long merchant name cannot break a table. */
    public static String clip(String s, int max) {
        if (s == null) return "—";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }
}
