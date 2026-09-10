package com.overwatch.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.CliException;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;

/**
 * {@code ow status} — is the stack up, and is it keeping up.
 *
 * <p>The first thing to run, and the reason it exists as its own command: the
 * failure mode of every other command is "could not reach the API", and that
 * sentence does not distinguish between the API being down, the broker being
 * down, and the simulator being paused. This does.
 *
 * <p>Exits non-zero when something is actually wrong, so it works as a health
 * check in a script rather than only as something to read. "Paused" is not
 * wrong — a paused simulator is a deliberate state — so it does not fail the
 * command, it is reported.
 */
@Command(name = "status",
        header = "Whether the stack is up, and whether it is keeping up.",
        description = {
                "",
                "Reports the API's health, the pipeline's throughput, the simulator's state",
                "and the consumer lag. Exits non-zero if the API is unreachable or unhealthy,",
                "so it can be used as a health check.",
                ""
        })
public class StatusCommand extends Base {

    @Override
    public Integer call() {
        Api api = api();

        if (!quiet()) {
            Out.line(Ansi.bold("Overwatch") + Ansi.dim("  ·  " + api.baseUrl()));
        }

        // Health first and on its own: if this fails, nothing below it can
        // succeed and a wall of "could not reach the API" repeated four times
        // is worse than one line saying the API is down.
        String health;
        try {
            health = Api.text(api.get("/actuator/health"), "status", "UNKNOWN");
        } catch (CliException e) {
            Out.fail(e.getMessage());
            return 1;
        }

        boolean healthy = "UP".equals(health);
        Out.heading("Services");
        Out.line(row("fraud-api", healthy ? Ansi.green("UP") : Ansi.red(health),
                api.baseUrl()));

        // Everything past here is best effort. A stack with the API up and the
        // simulator down is a real and useful state, and the command's job is
        // to describe it rather than to abort at the first gap.
        printSimulator(quietly(() -> api.get("/api/simulator/status")));
        printLastHour(quietly(() ->
                api.get("/api/stats/dashboard", Api.body("rangeMinutes", 60))));
        printGroups(quietly(() -> api.get("/api/streams/groups")));

        if (!quiet()) {
            Out.line();
            Out.note("`ow top` for a live view. `ow alerts list` for what has been flagged.");
        }
        return healthy ? 0 : 1;
    }

    private static void printSimulator(JsonNode sim) {
        if (sim == null) {
            Out.line(row("simulator", Ansi.yellow("UNREACHABLE"),
                    "not running in this stack, or still starting"));
            return;
        }
        boolean running = Api.at(sim, "running").asBoolean(false);
        Out.line(row("simulator", running ? Ansi.green("RUNNING") : Ansi.yellow("PAUSED"),
                Out.count((long) Api.number(sim, "transactionsPerSecond")) + "/s target, "
                        + Out.percent(Api.number(sim, "fraudInjectionRate"))
                        + " fraud injection"));
    }

    private static void printLastHour(JsonNode stats) {
        if (stats == null) return;
        Out.heading("Last hour");
        long transactions = Api.integer(stats, "transactionsLastHour");
        Out.line(figure("Transactions", Out.count(transactions)));
        Out.line(figure("Alerts", Out.count(Api.integer(stats, "totalAlerts"))
                + Ansi.dim(" all time")));
        Out.line(figure("Open alerts", Out.count(Api.integer(stats, "openAlerts"))));
        Out.line(figure("Mean risk score", Out.score(Api.number(stats, "averageRiskScore"))));
        if (transactions > 0) {
            Out.line(figure("Alert rate",
                    Out.percent((double) totalOf(stats) / transactions)
                            + Ansi.dim("  of the window's traffic")));
        }
    }

    private static void printGroups(JsonNode groups) {
        if (groups == null || !groups.isArray() || groups.isEmpty()) return;
        Out.heading("Consumer groups");
        for (JsonNode group : groups) {
            long lag = Api.integer(group, "totalLag");
            // Any standing lag is worth colouring: this pipeline is meant to be
            // sub-second, so a four-figure backlog is the difference between
            // "working" and "behind and catching up".
            String lagText = lag == 0 ? Ansi.green("0")
                    : lag < 1000 ? Ansi.yellow(Out.count(lag))
                    : Ansi.red(Out.count(lag));
            Out.line(row(Api.text(group, "groupId", "?"),
                    Api.text(group, "state", "?"),
                    "lag " + lagText + Ansi.dim(", " + Api.integer(group, "members")
                            + " member(s)")));
        }
    }

    private static long totalOf(JsonNode stats) {
        JsonNode series = Api.at(stats, "alertsOverTime");
        long total = 0;
        for (JsonNode bucket : series) total += Api.integer(bucket, "count");
        return total;
    }

    /** A name, a state and a detail — the three columns of the services block. */
    private static String row(String name, String state, String detail) {
        return "  " + Ansi.pad(name, 14) + Ansi.pad(state, 22) + Ansi.dim(detail);
    }

    private static String figure(String label, String value) {
        return "  " + Ansi.pad(label, 18) + value;
    }

    /**
     * Run a call, or return null.
     *
     * <p>Used for everything that is not the health check, so one absent
     * service degrades one block of the report instead of the whole command.
     */
    private static JsonNode quietly(java.util.function.Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (CliException e) {
            return null;
        }
    }
}
