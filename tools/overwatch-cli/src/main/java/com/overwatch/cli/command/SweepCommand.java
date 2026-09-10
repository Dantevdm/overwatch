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
import java.util.Locale;

/**
 * {@code ow sweep} — where should this threshold sit.
 *
 * <p>Replay answers "what would this threshold have caught". Sweep answers
 * "where should it be", which is the question actually being asked, and one
 * pass over the history evaluates every candidate — seven thresholds cost one
 * trip through the data rather than seven.
 *
 * <p>With no {@code --values} it uses the ladder the API suggests for that rule,
 * which is the common case: you want the shape of the curve before you have an
 * opinion about where on it to stand.
 */
@Command(name = "sweep",
        header = "Try many values for one rule parameter, in a single pass.",
        description = {
                "",
                "  ow sweep --rule HIGH_VALUE",
                "  ow sweep --rule HIGH_VALUE --values 20000,50000,80000 --hours 48",
                "",
                "With no --rule, lists what can be swept and what cannot, with the reason.",
                ""
        })
public class SweepCommand extends Base {

    @Option(names = { "-r", "--rule" }, paramLabel = "RULE_TYPE",
            description = "Rule to sweep, e.g. HIGH_VALUE. Omit to list the options.")
    private String ruleType;

    @Option(names = { "--parameter" }, paramLabel = "NAME",
            description = "Parameter to vary. Defaults to the one the API suggests.")
    private String parameter;

    @Option(names = { "-v", "--values" }, paramLabel = "V1,V2,…", split = ",",
            description = "Candidate values. Defaults to the suggested ladder.")
    private List<String> values;

    @Option(names = { "--hours" }, paramLabel = "N", defaultValue = "24",
            description = "How far back to replay. Default: ${DEFAULT-VALUE}")
    private int hours;

    @Override
    public Integer call() {
        JsonNode catalogue = api().get("/api/replay/sweepable");

        if (ruleType == null || ruleType.isBlank()) {
            return listOptions(catalogue);
        }

        String wanted = ruleType.toUpperCase(Locale.ROOT);
        JsonNode entry = entryFor(catalogue, wanted);
        String param = parameter != null && !parameter.isBlank()
                ? parameter
                : Api.text(entry, "parameter", null);

        JsonNode result = api().postJson("/api/replay/sweep", Api.body(
                "ruleType", wanted,
                "parameter", param,
                "values", candidates(entry),
                "hours", hours));

        Out.heading(wanted + "." + param + " over " + Api.integer(result, "hoursReplayed") + "h");
        Out.note("  " + Out.count(Api.integer(result, "transactionsEvaluated"))
                + " transactions evaluated"
                + (Api.at(result, "capped").asBoolean(false)
                   ? Ansi.yellow("  (capped — this is a sample of the window)") : ""));
        Out.line();

        curve(Api.at(result, "points"), param, Api.text(entry, "unit", "")).print();

        if (!quiet()) {
            Out.line();
            // The sweep says what each value would catch, not which is right.
            // That is a judgement about the cost of a false positive, and the
            // tool should not pretend to make it.
            Out.note("This says what each value would have caught, not which one is correct —");
            Out.note("that depends on what a false positive costs, which is not in the data.");
        }
        return 0;
    }

    /**
     * The catalogue entry for a rule, or a refusal that says why.
     *
     * <p>Every rule is in the catalogue; the ones that cannot be swept are the
     * ones with no parameter, and each carries its reason. Saying why beats
     * rejecting — "VELOCITY needs card history replay does not have" tells you
     * to reach for shadow mode, and "unknown rule" tells you nothing.
     */
    private JsonNode entryFor(JsonNode catalogue, String wanted) {
        for (JsonNode candidate : Api.at(catalogue, "sweepable")) {
            if (wanted.equals(Api.text(candidate, "ruleType", null))) {
                if (!sweepable(candidate) && (parameter == null || parameter.isBlank())) {
                    throw new CliException(wanted + " cannot be swept: "
                            + Api.text(candidate, "reason", "no reason given"));
                }
                return candidate;
            }
        }
        throw new CliException("Unknown rule '" + wanted
                + "'. Run `ow sweep` with no --rule to see the options.");
    }

    /**
     * The curve itself: one row per candidate, with a bar scaled to the peak.
     *
     * <p>Scaled to the busiest value rather than to the traffic, because the
     * question is the shape of the trade-off between two candidates, and at
     * one percent of traffic every bar scaled to the traffic is empty.
     */
    private static Out.Table curve(JsonNode points, String param, String unit) {
        double peak = 0;
        for (JsonNode point : points) {
            peak = Math.max(peak, Api.number(point, "wouldHaveFired"));
        }

        Out.Table table = new Out.Table()
                .column(param + (unit.isEmpty() ? "" : " (" + unit + ")"), Out.Align.RIGHT)
                .column("would fire", Out.Align.RIGHT)
                .column("of traffic", Out.Align.RIGHT)
                .column("");
        for (JsonNode point : points) {
            double fired = Api.number(point, "wouldHaveFired");
            table.row(
                    value(Api.at(point, "value")),
                    Out.count((long) fired),
                    String.format(Locale.ROOT, "%.2f%%", Api.number(point, "firePercentage")),
                    Ansi.dim(Out.bar(peak == 0 ? 0 : fired / peak, 26)));
        }
        return table;
    }

    /**
     * A candidate value as it was written, not as a double.
     *
     * <p>A threshold of 50000 sent back as {@code 50000.0} is the same number
     * and a worse answer: the column is the value you would type into the rule,
     * and it should read the way you would type it.
     */
    private static String value(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return "—";
        if (node.isIntegralNumber()) return Out.count(node.asLong());
        if (node.isFloatingPointNumber()) {
            double d = node.asDouble();
            return d == Math.rint(d) ? Out.count((long) d)
                    : String.format(Locale.ROOT, "%s", d);
        }
        return node.asText();
    }

    private List<Object> candidates(JsonNode entry) {
        List<Object> out = new ArrayList<>();
        if (values != null && !values.isEmpty()) {
            for (String raw : values) {
                String trimmed = raw.trim();
                // Sent as a number when it looks like one, because the rules
                // compare numerically and a quoted "50000" is not a threshold.
                try {
                    // parseX rather than valueOf: the same result, without
                    // boxing a primitive purely to parse it.
                    out.add(trimmed.contains(".")
                            ? (Object) Double.parseDouble(trimmed)
                            : (Object) Long.parseLong(trimmed));
                } catch (NumberFormatException e) {
                    out.add(trimmed);
                }
            }
            return out;
        }
        for (JsonNode suggested : Api.at(entry, "suggested")) {
            out.add(suggested.isIntegralNumber() ? suggested.asLong() : suggested.asDouble());
        }
        if (out.isEmpty()) {
            throw new CliException("No suggested values for this rule — pass --values.");
        }
        return out;
    }

    /**
     * A rule can be swept when the catalogue names a parameter to vary.
     *
     * <p>The API returns one list with every rule in it, and marks the ones it
     * cannot sweep by leaving {@code parameter} null rather than by omitting
     * them — so the reason travels with the rule.
     */
    private static boolean sweepable(JsonNode entry) {
        String param = Api.text(entry, "parameter", null);
        return param != null && !param.isBlank();
    }

    private int listOptions(JsonNode catalogue) {
        List<JsonNode> can = new ArrayList<>();
        List<JsonNode> cannot = new ArrayList<>();
        for (JsonNode entry : Api.at(catalogue, "sweepable")) {
            (sweepable(entry) ? can : cannot).add(entry);
        }

        Out.heading("Sweepable");
        for (JsonNode entry : can) {
            String unit = Api.text(entry, "unit", "");
            Out.line("  " + Ansi.bold(Ansi.pad(Api.text(entry, "ruleType", "—"), 22))
                    + Ansi.dim(Api.text(entry, "parameter", "")
                    + (unit.isEmpty() ? "" : "  " + unit)));
            Out.line("    " + Ansi.dim(Api.text(entry, "reason", "")));
        }

        if (!cannot.isEmpty()) {
            Out.heading("Not sweepable");
            for (JsonNode entry : cannot) {
                Out.line("  " + Ansi.pad(Api.text(entry, "ruleType", "—"), 22));
                Out.line("    " + Ansi.dim(Api.text(entry, "reason", "")));
            }
        }

        if (!quiet()) {
            Out.line();
            Out.note("`ow sweep --rule HIGH_VALUE` sweeps one. Shadow mode covers the rest:");
            Out.note("`ow rules state <id> SHADOW` scores a rule against live traffic without alerting.");
        }
        return 0;
    }
}
