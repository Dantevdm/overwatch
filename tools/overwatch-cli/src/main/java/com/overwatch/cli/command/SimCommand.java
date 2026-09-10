package com.overwatch.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Locale;

/**
 * {@code ow sim …} — drive the transaction stream.
 *
 * <p>{@code inject} is the demonstration move. {@code ow sim inject COMPOUND}
 * puts one large, round, foreign, small-hours transaction on the stream, and a
 * CRITICAL alert with five contributing rules arrives about a second later —
 * which is the whole system in one command.
 */
@Command(name = "sim", aliases = { "simulator" },
        header = "Drive the transaction stream: start, pause, rate, inject.",
        subcommands = {
                SimCommand.StatusCommand.class,
                SimCommand.StartCommand.class,
                SimCommand.PauseCommand.class,
                SimCommand.RateCommand.class,
                SimCommand.InjectCommand.class,
        })
public class SimCommand extends Base {

    @Override
    public Integer call() {
        return usage();
    }

    /** Shared by every subcommand here, so the state is echoed after each change. */
    static void printStatus(JsonNode status, boolean quiet) {
        boolean running = Api.at(status, "running").asBoolean(false);
        Out.line("  " + Ansi.pad("State", 22)
                + (running ? Ansi.green("RUNNING") : Ansi.yellow("PAUSED")));
        Out.line("  " + Ansi.pad("Target rate", 22)
                + Out.count((long) Api.number(status, "transactionsPerSecond")) + "/s");
        Out.line("  " + Ansi.pad("Fraud injection", 22)
                + Out.percent(Api.number(status, "fraudInjectionRate")));
        Out.line("  " + Ansi.pad("Published", 22)
                + Out.count(Api.integer(status, "published"))
                + Ansi.dim("  (" + Out.count(Api.integer(status, "fraudPublished"))
                + " with an injected pattern)"));
        if (!quiet) {
            JsonNode patterns = Api.at(status, "availablePatterns");
            if (patterns.isArray() && !patterns.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (JsonNode p : patterns) {
                    if (names.length() > 0) names.append(", ");
                    names.append(p.asText());
                }
                Out.line();
                Out.note("Patterns: " + names);
            }
        }
    }

    @Command(name = "status", header = "Whether the stream is running, and how fast.")
    public static class StatusCommand extends Base {
        @Override
        public Integer call() {
            printStatus(api().get("/api/simulator/status"), quiet());
            return 0;
        }
    }

    @Command(name = "start", header = "Resume publishing transactions.")
    public static class StartCommand extends Base {
        @Override
        public Integer call() {
            printStatus(api().post("/api/simulator/start"), quiet());
            return 0;
        }
    }

    @Command(name = "pause",
            header = "Stop publishing, without clearing anything.",
            description = {
                    "",
                    "The store keeps everything it has. This is the command to run before reading",
                    "an alert out loud, so the list stops moving under you.",
                    ""
            })
    public static class PauseCommand extends Base {
        @Override
        public Integer call() {
            printStatus(api().post("/api/simulator/pause"), quiet());
            return 0;
        }
    }

    @Command(name = "rate", header = "Change throughput, in transactions per second.")
    public static class RateCommand extends Base {

        @Parameters(index = "0", paramLabel = "PER_SECOND",
                description = "Target rate. Capped at 500 by the simulator.")
        private int perSecond;

        @Override
        public Integer call() {
            printStatus(api().post("/api/simulator/rate",
                    Api.body("perSecond", perSecond)), quiet());
            return 0;
        }
    }

    @Command(name = "inject",
            header = "Publish one specific fraud pattern, right now.",
            description = {
                    "",
                    "COMPOUND is the one to reach for: a large, round, foreign, small-hours",
                    "transaction that trips five rules at once, so a CRITICAL alert with all five",
                    "reasons attached appears about a second later.",
                    ""
            })
    public static class InjectCommand extends Base {

        @Parameters(index = "0", paramLabel = "PATTERN",
                description = "HIGH_VALUE, VELOCITY_BURST, LATE_NIGHT, ROUND_AMOUNT, "
                        + "CROSS_BORDER, HIGH_RISK_CATEGORY or COMPOUND.")
        private String pattern;

        @Override
        public Integer call() {
            JsonNode result = api().post(
                    "/api/simulator/inject/" + pattern.toUpperCase(Locale.ROOT));
            Out.ok("Injected " + Ansi.bold(pattern.toUpperCase(Locale.ROOT))
                    + Ansi.dim("  " + Api.text(result, "transactionId", "")));
            if (!quiet()) {
                Out.note("`ow alerts watch` to see it land, or `ow alerts list -n 1` in a second.");
            }
            return 0;
        }
    }
}
