package com.overwatch.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.CliException;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code ow alerts …} — the alert list, one alert, and the live feed.
 *
 * <p>Grouped rather than flattened into {@code ow list-alerts} and friends,
 * because the noun is the thing being worked on and the verbs are what you do
 * to it. It also means {@code ow alerts} with no verb can print the four things
 * you might have meant.
 */
@Command(name = "alerts",
        header = "Alerts: list them, read one, or follow them as they arrive.",
        subcommands = {
                AlertsCommand.ListCommand.class,
                AlertsCommand.ShowCommand.class,
                AlertsCommand.WatchCommand.class,
                AlertsCommand.StatusCommand.class,
        })
public class AlertsCommand extends Base {

    @Override
    public Integer call() {
        return usage();
    }

    // ---- ow alerts list ----------------------------------------------------

    @Command(name = "list",
            header = "The most recent alerts, newest first.",
            description = {
                    "",
                    "Ten rows by default, the same as the web console — a screenful, not a scroll.",
                    "",
                    "The score column is worth reading next to the severity: a 0.30 LOW is one rule",
                    "firing on its own, and a 0.65 HIGH is three agreeing.",
                    ""
            })
    public static class ListCommand extends Base {

        @Option(names = { "-s", "--severity" }, paramLabel = "BAND",
                description = "One of LOW, MEDIUM, HIGH, CRITICAL.")
        private String severity;

        @Option(names = { "--status" }, paramLabel = "STATUS",
                description = "OPEN, CONFIRMED_FRAUD, FALSE_POSITIVE or DISMISSED.")
        private String status;

        @Option(names = { "-n", "--limit" }, paramLabel = "N", defaultValue = "10",
                description = "Rows to show. Default: ${DEFAULT-VALUE}")
        private int limit;

        @Option(names = { "-p", "--page" }, paramLabel = "N", defaultValue = "0",
                description = "Page, zero-based. Default: ${DEFAULT-VALUE}")
        private int page;

        @Override
        public Integer call() {
            JsonNode response = api().get("/api/alerts", Api.body(
                    "size", limit, "page", page,
                    "severity", blankToNull(severity),
                    "status", blankToNull(status)));

            JsonNode rows = Api.at(response, "content");
            if (!rows.isArray() || rows.isEmpty()) {
                Out.note("No alerts match.");
                return 0;
            }

            Out.Table table = new Out.Table()
                    .column("id")
                    .column("when")
                    .column("severity")
                    .column("score", Out.Align.RIGHT)
                    .column("amount", Out.Align.RIGHT)
                    .column("status");
            for (JsonNode alert : rows) {
                table.row(
                        Ansi.dim(Out.shortId(Api.text(alert, "id", null))),
                        Out.dateTime(Api.text(alert, "occurredAt", null)),
                        Out.severity(Api.text(alert, "severity", null)),
                        Out.score(Api.number(alert, "riskScore")),
                        Out.zar(Api.number(alert, "amount")),
                        Out.status(Api.text(alert, "status", null)));
            }
            table.print();

            if (!quiet()) {
                long total = Api.integer(response, "totalElements");
                Out.line();
                Out.note(Out.count(rows.size()) + " of " + Out.count(total) + " alerts"
                        + (total > (long) (page + 1) * limit
                        ? "  ·  --page " + (page + 1) + " for the next " + limit
                        : ""));
                Out.note("`ow alerts show <id>` for one alert and the rules behind it.");
            }
            return 0;
        }
    }

    // ---- ow alerts show ----------------------------------------------------

    @Command(name = "show",
            header = "One alert, with every rule that contributed and why.",
            description = {
                    "",
                    "The id may be shortened to its first segment, the way it is printed by",
                    "`ow alerts list` — anything unambiguous is resolved against the recent list.",
                    ""
            })
    public static class ShowCommand extends Base {

        @Parameters(index = "0", paramLabel = "ID",
                description = "Alert id, full or shortened.")
        private String id;

        @Override
        public Integer call() {
            JsonNode alert = fetch(id);

            Out.line();
            Out.line(Ansi.bold(Out.zar(Api.number(alert, "amount"))) + "   "
                    + Out.severity(Api.text(alert, "severity", null)) + "  "
                    + Ansi.dim("score " + Out.score(Api.number(alert, "riskScore"))));
            Out.line(Ansi.dim(Api.text(alert, "id", "—")));

            Out.heading("When");
            field("Transaction", Out.dateTime(Api.text(alert, "occurredAt", null))
                    + Ansi.dim("  " + Out.ago(Api.text(alert, "occurredAt", null))));
            // Both timestamps, deliberately. The gap between them is the
            // pipeline's end-to-end latency for this one transaction, and it is
            // the number people ask about first.
            field("Alert raised", Out.dateTime(Api.text(alert, "createdAt", null)));
            field("Status", Out.status(Api.text(alert, "status", null)));

            JsonNode hits = Api.at(alert, "hits");
            Out.heading("Why — " + (hits.isArray() ? hits.size() : 0) + " rule(s) fired");
            if (!hits.isArray() || hits.isEmpty()) {
                Out.note("No rule detail stored for this alert.");
            } else {
                double sum = 0;
                for (JsonNode hit : hits) {
                    double weight = Api.number(hit, "weight");
                    sum += weight;
                    Out.line("  " + Ansi.bold(Api.text(hit, "ruleType", "?"))
                            + Ansi.dim("  +" + Out.score(weight)));
                    Out.line("    " + Api.text(hit, "reason", "—"));
                    JsonNode evidence = Api.at(hit, "evidence");
                    if (evidence.isObject() && !evidence.isEmpty()) {
                        List<String> pairs = new ArrayList<>();
                        evidence.properties().forEach(
                                e -> pairs.add(e.getKey() + "=" + e.getValue().asText()));
                        Out.line("    " + Ansi.dim(String.join("  ", pairs)));
                    }
                }
                double score = Api.number(alert, "riskScore");
                Out.line();
                Out.line("  " + Ansi.pad("Weights sum", 20) + Out.score(sum));
                Out.line("  " + Ansi.pad("Risk score", 20) + Out.score(score));
                if (sum > score + 0.001) {
                    // The cap is the single most confusing thing about this
                    // model when you first see it, so it is explained at the
                    // exact moment the two numbers disagree.
                    Out.note("  Capped at 1.0 — the weights over-sum, which is why these differ.");
                }
            }

            if (!quiet()) {
                Out.line();
                Out.note("Transaction: " + Api.text(alert, "transactionId", "—"));
            }
            return 0;
        }

        /**
         * Resolve a possibly-shortened id.
         *
         * <p>Tried as given first, because a full id should cost one request.
         * Only on a 404 does it scan the recent list for a unique prefix —
         * which is what makes the ids printed by {@code list} usable as typed,
         * and an ambiguous prefix an error rather than a coin toss.
         */
        private JsonNode fetch(String wanted) {
            try {
                return api().get("/api/alerts/" + wanted);
            } catch (CliException notFound) {
                JsonNode page = api().get("/api/alerts", Api.body("size", 200));
                List<JsonNode> matches = new ArrayList<>();
                for (JsonNode alert : Api.at(page, "content")) {
                    if (Api.text(alert, "id", "").startsWith(wanted)) matches.add(alert);
                }
                if (matches.size() == 1) {
                    return api().get("/api/alerts/" + Api.text(matches.get(0), "id", ""));
                }
                if (matches.size() > 1) {
                    throw new CliException("'" + wanted + "' matches " + matches.size()
                            + " recent alerts. Use more of the id.");
                }
                throw notFound;
            }
        }

        private static void field(String label, String value) {
            Out.line("  " + Ansi.pad(label, 16) + value);
        }
    }

    // ---- ow alerts watch ---------------------------------------------------

    @Command(name = "watch",
            header = "Follow alerts as they are raised.",
            description = {
                    "",
                    "One line per new alert, appended as it arrives — the terminal equivalent of",
                    "leaving the dashboard open, and the thing to have running in a second pane",
                    "while you change a rule in the first.",
                    "",
                    "Polls rather than streams: the API is request/response, and adding a socket",
                    "for a demo tool would be a second protocol to keep working. At two seconds",
                    "the delay is shorter than the time it takes to read the previous line.",
                    ""
            })
    public static class WatchCommand extends Base {

        @Option(names = { "-i", "--interval" }, paramLabel = "SECONDS", defaultValue = "2",
                description = "Poll interval. Default: ${DEFAULT-VALUE}")
        private int interval;

        @Option(names = { "-s", "--severity" }, paramLabel = "BAND",
                description = "Only this band.")
        private String severity;

        @Override
        public Integer call() throws InterruptedException {
            if (!quiet()) {
                Out.note("Following alerts from " + api().baseUrl()
                        + " — ctrl-c to stop.");
                Out.line();
            }

            // Ids already printed. Bounded, because this can run for hours and
            // an unbounded set of every id ever seen is a slow leak; 5000 is
            // far more than one poll can return, so nothing can be reprinted.
            Set<String> seen = new HashSet<>();
            boolean first = true;

            while (true) {
                JsonNode response;
                try {
                    response = api().get("/api/alerts", Api.body(
                            "size", 20, "severity", blankToNull(severity)));
                } catch (CliException e) {
                    // A transient failure must not end a watch — the whole
                    // point is that it survives a service restart.
                    Out.warn(e.getMessage());
                    Thread.sleep(interval * 1000L);
                    continue;
                }

                List<JsonNode> arrivals = new ArrayList<>();
                for (JsonNode alert : Api.at(response, "content")) {
                    String id = Api.text(alert, "id", null);
                    if (id != null && seen.add(id)) arrivals.add(alert);
                }
                if (seen.size() > 5000) seen.clear();

                // The first poll returns the existing backlog, which is not
                // "arriving". Printing it would fill the screen with history
                // the moment you start watching.
                if (first) {
                    first = false;
                } else {
                    printArrivals(arrivals);
                }
                Thread.sleep(interval * 1000L);
            }
        }

        /**
         * Oldest first.
         *
         * <p>The API returns newest first and a feed reads downward, so the
         * list is walked backwards: the newest line has to be the last one
         * printed or the feed scrolls the wrong way.
         */
        private static void printArrivals(List<JsonNode> arrivals) {
            for (int i = arrivals.size() - 1; i >= 0; i--) {
                JsonNode alert = arrivals.get(i);
                Out.line(Ansi.dim(Out.time(Api.text(alert, "occurredAt", null))) + "  "
                        + Out.severity(Api.text(alert, "severity", null)) + "  "
                        + Ansi.padLeft(Out.zar(Api.number(alert, "amount")), 14) + "  "
                        + Ansi.dim("score " + Out.score(Api.number(alert, "riskScore"))
                        + "  " + Out.shortId(Api.text(alert, "id", null))));
            }
        }
    }

    // ---- ow alerts status --------------------------------------------------

    @Command(name = "status",
            header = "Set an alert's status — triage it from the terminal.",
            description = {
                    "",
                    "The same PATCH the web console sends. CONFIRMED_FRAUD and FALSE_POSITIVE are",
                    "the two that matter: they are how a rule's precision gets measured, so they",
                    "are worth setting even in a demo.",
                    ""
            })
    public static class StatusCommand extends Base {

        @Parameters(index = "0", paramLabel = "ID", description = "Alert id.")
        private String id;

        @Parameters(index = "1", paramLabel = "STATUS",
                description = "OPEN, CONFIRMED_FRAUD, FALSE_POSITIVE or DISMISSED.")
        private String status;

        @Override
        public Integer call() {
            JsonNode updated = api().patch("/api/alerts/" + id + "/status",
                    Api.body("status", status.toUpperCase(java.util.Locale.ROOT)));
            Out.ok("Alert " + Out.shortId(Api.text(updated, "id", id)) + " is now "
                    + Out.status(Api.text(updated, "status", status)));
            return 0;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.toUpperCase(java.util.Locale.ROOT);
    }
}
