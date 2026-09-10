package com.overwatch.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code ow cardholder …} — the directory, and one person's profile.
 *
 * <p>The profile is the screen that turns a flag into a decision, and it reads
 * the same way in a terminal as it does in the browser: who they are, what they
 * normally do, and what has been raised against them. A frequent traveller
 * tripping the cross-border rule every week looks like fraud in an alert list
 * and looks like a frequent traveller here.
 */
@Command(name = "cardholder", aliases = { "customers" },
        header = "Cardholders: the directory, and one person's history.",
        subcommands = {
                CardholderCommand.ListCommand.class,
                CardholderCommand.ShowCommand.class,
        })
public class CardholderCommand extends Base {

    @Override
    public Integer call() {
        return usage();
    }

    @Command(name = "list", header = "The cardholder directory, busiest first.")
    public static class ListCommand extends Base {

        // Not -q: that is the root's --quiet, which is an inherited option and
        // therefore present on every subcommand. picocli catches the collision
        // at startup rather than silently letting one win, which is the right
        // trade — but it means a global short flag is spent everywhere.
        @Option(names = { "-s", "--search" }, paramLabel = "TEXT",
                description = "Name or reference, partial match.")
        private String search;

        @Option(names = { "-n", "--limit" }, paramLabel = "N", defaultValue = "10",
                description = "Rows to show. Default: ${DEFAULT-VALUE}")
        private int limit;

        @Override
        public Integer call() {
            JsonNode response = api().get("/api/customers", Api.body(
                    "size", limit,
                    "search", search == null || search.isBlank() ? null : search));

            JsonNode rows = Api.at(response, "content");
            if (!rows.isArray() || rows.isEmpty()) {
                Out.note("No cardholders match.");
                return 0;
            }

            Out.Table table = new Out.Table()
                    .column("reference")
                    .column("name")
                    .column("txns", Out.Align.RIGHT)
                    .column("spend", Out.Align.RIGHT)
                    .column("cards", Out.Align.RIGHT)
                    .column("alerts", Out.Align.RIGHT)
                    .column("last seen");
            for (JsonNode row : rows) {
                long alerts = Api.integer(row, "alerts");
                table.row(
                        Ansi.dim(Api.text(row, "id", "—")),
                        Out.clip(Api.text(row, "name", "—"), 24),
                        Out.count(Api.integer(row, "transactions")),
                        Out.zar(Api.number(row, "totalSpend")),
                        String.valueOf(Api.integer(row, "cards")),
                        alerts == 0 ? Ansi.dim("0") : Ansi.yellow(Out.count(alerts)),
                        Out.ago(Api.text(row, "lastSeen", null)));
            }
            table.print();

            if (!quiet()) {
                Out.line();
                Out.note("`ow cardholder show <reference>` for one person's profile.");
            }
            return 0;
        }
    }

    @Command(name = "show", header = "One cardholder: their habits, and what was raised.")
    public static class ShowCommand extends Base {

        @Parameters(index = "0", paramLabel = "REFERENCE",
                description = "Cardholder reference, e.g. cust-01595.")
        private String id;

        @Override
        public Integer call() {
            JsonNode profile = api().get("/api/customers/" + id);

            Out.line();
            Out.line(Ansi.bold(Api.text(profile, "name", "—")) + "  "
                    + Ansi.dim(Api.text(profile, "id", "—")));

            JsonNode risk = Api.at(profile, "risk");
            Out.line("  " + riskBand(Api.text(risk, "band", "—"))
                    + Ansi.dim("  score " + Api.integer(risk, "score") + "/100"));

            printIdentity(profile);
            printSpend(profile);
            printCategories(Api.at(profile, "byCategory"));
            printSignals(risk);
            printAlerts(Api.at(profile, "alerts"));
            return 0;
        }

        private static void printIdentity(JsonNode profile) {
            Out.heading("Who they are");
            field("Home city", Api.text(profile, "homeCity", "—"));
            field("Bank", Api.text(profile, "bank", "—"));
            field("Cards", String.valueOf(Api.at(profile, "cards").size()));
            field("First seen", Out.dateTime(Api.text(profile, "firstSeen", null)));
            field("Last seen", Out.dateTime(Api.text(profile, "lastSeen", null))
                    + Ansi.dim("  " + Out.ago(Api.text(profile, "lastSeen", null))));
        }

        private static void printSpend(JsonNode profile) {
            Out.heading("What they spend");
            field("Transactions", Out.count(Api.integer(profile, "transactions"))
                    + (Api.at(profile, "historyCapped").asBoolean(false)
                       // Said out loud rather than left for the reader to
                       // wonder about: a summary of a sample that does not
                       // admit it is a sample is worse than no summary.
                       ? Ansi.yellow("  (history capped — this is a sample)") : ""));
            field("Total", Out.zar(Api.number(profile, "totalSpend")));
            field("Average", Out.zar(Api.number(profile, "averageAmount")));
            field("Largest", Out.zar(Api.number(profile, "largestAmount")));
        }

        private static void printCategories(JsonNode byCategory) {
            if (!byCategory.isArray() || byCategory.isEmpty()) return;
            Out.heading("Where");
            int shown = 0;
            for (JsonNode row : byCategory) {
                if (shown++ >= 6) break;
                double share = Api.number(row, "share");
                Out.line("  " + Ansi.pad(Api.text(row, "label", "—"), 14)
                        + Ansi.dim(Out.bar(share, 24)) + "  "
                        + Ansi.padLeft(Out.zar(Api.number(row, "value")), 14) + "  "
                        + Ansi.dim(Ansi.padLeft(Out.percent(share), 6)));
            }
        }

        private void printSignals(JsonNode risk) {
            JsonNode signals = Api.at(risk, "signals");
            if (!signals.isArray() || signals.isEmpty()) return;
            Out.heading("Why the score");
            for (JsonNode signal : signals) {
                boolean elevated = Api.at(signal, "elevated").asBoolean(false);
                Out.line("  " + (elevated ? Ansi.yellow("● ") : Ansi.dim("○ "))
                        + Ansi.pad(Api.text(signal, "label", "—"), 26)
                        + Ansi.dim(Api.text(signal, "detail", "")));
            }
            // The caveat is part of the score, not a footnote to it. A
            // heuristic presented without one gets read as a model.
            String caveat = Api.text(risk, "caveat", null);
            if (caveat != null && !quiet()) {
                Out.line();
                Out.note("  " + caveat);
            }
        }

        private static void printAlerts(JsonNode alerts) {
            if (!alerts.isArray() || alerts.isEmpty()) return;
            Out.heading("Alerts (" + alerts.size() + ")");
            Out.Table table = new Out.Table()
                    .column("when").column("severity")
                    .column("amount", Out.Align.RIGHT).column("rules");
            int shown = 0;
            for (JsonNode alert : alerts) {
                if (shown++ >= 10) break;
                StringBuilder rules = new StringBuilder();
                for (JsonNode rule : Api.at(alert, "rules")) {
                    if (rules.length() > 0) rules.append(", ");
                    rules.append(rule.asText());
                }
                table.row(Out.dateTime(Api.text(alert, "occurredAt", null)),
                        Out.severity(Api.text(alert, "severity", null)),
                        Out.zar(Api.number(alert, "amount")),
                        Ansi.dim(rules.toString()));
            }
            table.print();
        }

        private static String riskBand(String band) {
            return switch (band) {
                case "ELEVATED" -> Ansi.red(Ansi.bold(band));
                case "WATCH" -> Ansi.yellow(band);
                default -> Ansi.green(band);
            };
        }

        private static void field(String label, String value) {
            Out.line("  " + Ansi.pad(label, 16) + value);
        }
    }
}
