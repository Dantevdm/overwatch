package com.overwatch.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code ow rules …} — read the rule set, and change it.
 *
 * <p>The two write commands here are the reason to have a CLI at all: tuning is
 * a loop of change-something, watch-what-happens, and that loop is far better
 * as {@code ow rules weight 3 0.4} in one pane with {@code ow alerts watch} in
 * another than as two browser tabs.
 */
@Command(name = "rules",
        header = "The rule set: weights, thresholds and state.",
        subcommands = {
                RulesCommand.ListCommand.class,
                RulesCommand.StateCommand.class,
                RulesCommand.WeightCommand.class,
                RulesCommand.PerformanceCommand.class,
        })
public class RulesCommand extends Base {

    @Override
    public Integer call() {
        return usage();
    }

    @Command(name = "list", header = "Every rule, its state, weight and parameters.")
    public static class ListCommand extends Base {

        @Override
        public Integer call() {
            JsonNode rules = api().get("/api/rules");

            Out.Table table = new Out.Table()
                    .column("id", Out.Align.RIGHT)
                    .column("rule")
                    .column("state")
                    .column("weight", Out.Align.RIGHT)
                    .column("parameters");
            for (JsonNode rule : rules) {
                List<String> params = new ArrayList<>();
                // asText() on an array node returns an empty string, so an
                // allowedCountries=[] rendered as "allowedCountries=" — which
                // reads as a missing value rather than an empty list. They are
                // different things, and the empty one is the interesting one:
                // CROSS_BORDER with no allowed countries flags every foreign
                // transaction.
                Api.at(rule, "parameters").properties().forEach(
                        e -> params.add(e.getKey() + "=" + render(e.getValue())));
                table.row(
                        String.valueOf(Api.integer(rule, "id")),
                        Api.text(rule, "ruleType", "—"),
                        Out.ruleState(Api.text(rule, "state", null)),
                        Out.score(Api.number(rule, "weight")),
                        Ansi.dim(String.join("  ", params)));
            }
            table.print();

            if (!quiet()) {
                Out.line();
                // Worth restating every time the rule set is printed: the
                // weights are the model, and 0.30 is where alerting begins.
                Out.note("Weights accumulate. An alert needs 0.30 — one rule at 0.30, or "
                        + "two lighter ones together.");
                Out.note("`ow rules state <id> SHADOW` scores without alerting. "
                        + "`ow rules weight <id> 0.4` retunes.");
            }
            return 0;
        }
    }

    /** A parameter value as one line: arrays as [a, b], everything else as text. */
    private static String render(JsonNode value) {
        if (!value.isArray()) return value.asText();
        if (value.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (JsonNode item : value) {
            if (out.length() > 1) out.append(", ");
            out.append(item.asText());
        }
        return out.append("]").toString();
    }

    @Command(name = "state",
            header = "Enable, disable, or shadow a rule.",
            description = {
                    "",
                    "SHADOW is the interesting one: the rule keeps scoring and recording hits but",
                    "stops raising alerts, which is how a threshold change gets measured before it",
                    "reaches anyone's card.",
                    ""
            })
    public static class StateCommand extends Base {

        @Parameters(index = "0", paramLabel = "ID", description = "Rule id.")
        private long id;

        @Parameters(index = "1", paramLabel = "STATE",
                description = "ENABLED, DISABLED or SHADOW.")
        private String state;

        @Override
        public Integer call() {
            JsonNode updated = api().patch("/api/rules/" + id + "/state",
                    Api.body("state", state.toUpperCase(Locale.ROOT)));
            Out.ok(Api.text(updated, "ruleType", "Rule " + id) + " is now "
                    + Out.ruleState(Api.text(updated, "state", state)));
            return 0;
        }
    }

    @Command(name = "weight", header = "Change how much a rule contributes to the score.")
    public static class WeightCommand extends Base {

        @Parameters(index = "0", paramLabel = "ID", description = "Rule id.")
        private long id;

        @Parameters(index = "1", paramLabel = "WEIGHT", description = "0.0 to 1.0.")
        private double weight;

        @Override
        public Integer call() {
            JsonNode updated = api().patch("/api/rules/" + id + "/weight",
                    Api.body("weight", weight));
            String type = Api.text(updated, "ruleType", "Rule " + id);
            double now = Api.number(updated, "weight");
            Out.ok(type + " now contributes " + Out.score(now));
            if (!quiet() && now < 0.30) {
                // The one consequence people are surprised by, said at the
                // moment it becomes true rather than left to be discovered.
                Out.note("Below the 0.30 alert threshold, so this rule can no longer "
                        + "raise an alert on its own — only alongside another.");
            }
            return 0;
        }
    }

    @Command(name = "performance",
            header = "How often each rule fires, live and in shadow.")
    public static class PerformanceCommand extends Base {

        @Override
        public Integer call() {
            JsonNode rows = api().get("/api/rules/performance");
            if (!rows.isArray() || rows.isEmpty()) {
                Out.note("No rule hits recorded yet.");
                return 0;
            }

            long max = 1;
            for (JsonNode row : rows) max = Math.max(max, Api.integer(row, "timesFired"));

            Out.Table table = new Out.Table()
                    .column("rule")
                    .column("state")
                    .column("fired", Out.Align.RIGHT)
                    .column("share", Out.Align.RIGHT)
                    .column("")
                    .column("shadow", Out.Align.RIGHT);
            for (JsonNode row : rows) {
                long fired = Api.integer(row, "timesFired");
                long shadowHits = Api.integer(row, "shadowHits");
                table.row(
                        Api.text(row, "ruleType", "—"),
                        Out.ruleState(Api.text(row, "state", null)),
                        Out.count(fired),
                        Out.percent(Api.number(row, "shareOfAlerts")),
                        Ansi.dim(Out.bar((double) fired / max, 22)),
                        shadowHits == 0 ? Ansi.dim("—") : Ansi.cyan(Out.count(shadowHits)));
            }
            table.print();

            if (!quiet()) {
                Out.line();
                // falsePositiveRate is null until alerts are triaged, and a
                // blank column with no explanation reads as a broken query
                // rather than as an honest "nobody has looked yet".
                Out.note("Precision is not shown: it needs alerts triaged as confirmed or "
                        + "false positive, and this stack has none. `ow alerts status <id> "
                        + "FALSE_POSITIVE` starts filling it in.");
            }
            return 0;
        }
    }
}
