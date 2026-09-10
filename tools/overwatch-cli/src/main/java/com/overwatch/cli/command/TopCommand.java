package com.overwatch.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.CliException;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ow top} — the dashboard, refreshing in place.
 *
 * <p>Named after {@code top} because that is what it is: one screen that stays
 * current, for leaving open on a second monitor while you do something else. It
 * is the command this tool exists for — a browser tab can show the same figures
 * but cannot sit in a tmux pane next to the logs.
 *
 * <p>Redraws by clearing and reprinting rather than by moving the cursor around.
 * Partial repaints are how a terminal UI ends up with stale characters left over
 * from a longer previous line, and at one frame every two seconds the flicker
 * of a full clear is not worth the bookkeeping to avoid.
 */
@Command(name = "top",
        header = "A live view of the pipeline, refreshing in place.",
        description = {
                "",
                "Ctrl-C to stop. --once prints a single frame and exits, which is the form to",
                "use in a script or when piping to a file.",
                ""
        })
public class TopCommand extends Base {

    @Option(names = { "-i", "--interval" }, paramLabel = "SECONDS", defaultValue = "2",
            description = "Redraw interval. Default: ${DEFAULT-VALUE}")
    private int interval;

    @Option(names = { "-r", "--range" }, paramLabel = "MINUTES", defaultValue = "60",
            description = "Window the figures cover. Default: ${DEFAULT-VALUE}")
    private int rangeMinutes;

    @Option(names = { "--once" }, description = "Draw one frame and exit.")
    private boolean once;

    @Override
    public Integer call() throws InterruptedException {
        if (once) {
            draw();
            return 0;
        }

        // The cursor is hidden for the duration and restored on the way out —
        // including on ctrl-c, which is what the shutdown hook is for. A tool
        // that leaves the terminal without a cursor is a tool people stop
        // trusting with their shell.
        System.out.print(Ansi.hideCursor());
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.print(Ansi.showCursor() + System.lineSeparator())));

        while (true) {
            draw();
            Thread.sleep(interval * 1000L);
        }
    }

    private void draw() {
        JsonNode stats;
        try {
            stats = api().get("/api/stats/dashboard", Api.body("rangeMinutes", rangeMinutes));
        } catch (CliException e) {
            // Reported in place rather than thrown: a live view that exits the
            // moment the API blinks is useless precisely when you are watching
            // it because something is wrong.
            System.out.print(Ansi.clearScreen());
            Out.fail(e.getMessage());
            return;
        }

        StringBuilder frame = new StringBuilder(Ansi.clearScreen());
        frame.append(Ansi.banner(" OVERWATCH "))
             .append("  ").append(Ansi.dim(api().baseUrl()))
             .append("  ").append(Ansi.dim("last " + rangeMinutes + "m"))
             .append(System.lineSeparator()).append(System.lineSeparator());
        System.out.print(frame);

        long transactions = Api.integer(stats, "totalTransactions");
        long alerts = Api.integer(stats, "totalAlerts");
        long open = Api.integer(stats, "openAlerts");

        Out.line("  " + tile("Transactions", Out.count(transactions))
                + tile("Alerts", Out.count(alerts))
                + tile("Open", Out.count(open))
                + tile("Mean score", Out.score(Api.number(stats, "averageRiskScore"))));
        Out.line("  " + tile("Flagged (24h)", Out.zar(Api.number(stats, "flaggedLast24hZar"))));

        // Sparklines over the same series the dashboard charts, which is the
        // whole reason this is worth looking at rather than just the totals:
        // the shape says whether the last hour is normal.
        Out.heading("  Shape");
        sparkline("Transactions", Api.at(stats, "transactionsOverTime"));
        sparkline("Alerts", Api.at(stats, "alertsOverTime"));

        Out.heading("  Severity");
        JsonNode mix = Api.at(stats, "alertsBySeverity");
        long total = 0;
        for (String band : SEVERITIES) total += mix.path(band).asLong(0);
        for (String band : SEVERITIES) {
            long n = mix.path(band).asLong(0);
            double share = total == 0 ? 0 : (double) n / total;
            Out.line("  " + Out.severity(band) + "  "
                    + Ansi.dim(Out.bar(share, 28)) + "  "
                    + Ansi.padLeft(Out.count(n), 8) + "  "
                    + Ansi.dim(Ansi.padLeft(Out.percent(share), 6)));
        }

        JsonNode recent = safeRecent();
        if (recent != null && recent.isArray() && !recent.isEmpty()) {
            Out.heading("  Latest");
            int shown = 0;
            for (JsonNode alert : recent) {
                if (shown++ >= 6) break;
                Out.line("  " + Ansi.dim(Out.time(Api.text(alert, "occurredAt", null)))
                        + "  " + Out.severity(Api.text(alert, "severity", null))
                        + "  " + Ansi.padLeft(Out.zar(Api.number(alert, "amount")), 14)
                        + "  " + Ansi.dim("score " + Out.score(Api.number(alert, "riskScore"))));
            }
        }

        if (!once) {
            Out.line();
            Out.note("  refreshing every " + interval + "s — ctrl-c to stop");
        }
    }

    private static final String[] SEVERITIES = { "CRITICAL", "HIGH", "MEDIUM", "LOW" };

    private JsonNode safeRecent() {
        try {
            return Api.at(api().get("/api/alerts", Api.body("size", 6)), "content");
        } catch (CliException e) {
            return null;
        }
    }

    private void sparkline(String label, JsonNode series) {
        List<Long> values = new ArrayList<>();
        for (JsonNode bucket : series) values.add(Api.integer(bucket, "count"));
        if (values.isEmpty()) return;
        long peak = values.stream().mapToLong(Long::longValue).max().orElse(0);
        Out.line("  " + Ansi.pad(label, 14) + Ansi.cyan(Out.sparkline(values))
                + "  " + Ansi.dim("peak " + Out.count(peak)));
    }

    /** A labelled figure, padded so four of them line up as columns. */
    private static String tile(String label, String value) {
        return Ansi.pad(Ansi.dim(label) + " " + Ansi.bold(value), 30);
    }
}
