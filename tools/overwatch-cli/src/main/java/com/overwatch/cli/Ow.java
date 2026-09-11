package com.overwatch.cli;

import com.overwatch.cli.command.AlertsCommand;
import com.overwatch.cli.command.CardholderCommand;
import com.overwatch.cli.command.ReportCommand;
import com.overwatch.cli.command.ResetCommand;
import com.overwatch.cli.command.RulesCommand;
import com.overwatch.cli.command.SimCommand;
import com.overwatch.cli.command.StatusCommand;
import com.overwatch.cli.command.SweepCommand;
import com.overwatch.cli.command.TopCommand;
import com.overwatch.cli.command.TransactionsCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * {@code ow} — the fraud console as a command-line tool.
 *
 * <p>Why this exists next to a perfectly good web UI: every screen in the
 * browser is a thin client over the same HTTP API, so a terminal client is the
 * same domain through a different door — and the terminal is better at some of
 * the jobs. Watching alerts arrive while you change a rule threshold is two
 * panes, not two tabs. Answering "what did the pipeline do overnight" is a
 * pipe into {@code grep} rather than a page of filters. And a demonstration
 * that can be driven from a script is a demonstration that can be rehearsed.
 *
 * <p>It talks to the BFF and nothing else — no database credentials, no broker
 * connection, no second copy of the domain logic. Anything it can do, the
 * browser can do, which is the property that keeps the two from drifting.
 *
 * <p>Deliberately not a Spring Boot application. There is no context to build,
 * so it answers in about a fifth of a second; a CLI that takes as long as a
 * page load is a CLI nobody reaches for.
 */
@Command(
        name = "ow",
        version = "Overwatch CLI " + Ow.VERSION,
        mixinStandardHelpOptions = true,
        synopsisSubcommandLabel = "COMMAND",
        header = "The Overwatch fraud console, from the terminal.",
        descriptionHeading = "%n",
        description = {
                "",
                "Talks to the same API the browser does. Start with @|bold ow status|@ to check the",
                "stack is up, then @|bold ow top|@ for the live pipeline view.",
                ""
        },
        footerHeading = "%nExamples:%n",
        footer = {
                "  ow status                            is everything up",
                "  ow top                               live pipeline view, refreshing",
                "  ow alerts list --severity HIGH       the ones worth reading",
                "  ow alerts watch                      follow alerts as they land",
                "  ow alerts show 6bdc4f64              one alert and its reasoning",
                "  ow rules list                        thresholds and weights",
                "  ow rules state 2 SHADOW              score without alerting",
                "  ow sim inject COMPOUND               five rules at once, on demand",
                "  ow sweep --rule HIGH_VALUE           what a threshold change would have caught",
                "",
                "The API address comes from --api, then OVERWATCH_API, then localhost:8080.",
                "`make urls` prints the address on this machine — the port moves if 8080 was taken."
        },
        subcommands = {
                StatusCommand.class,
                TopCommand.class,
                AlertsCommand.class,
                TransactionsCommand.class,
                CardholderCommand.class,
                RulesCommand.class,
                SimCommand.class,
                SweepCommand.class,
                ReportCommand.class,
                ResetCommand.class,
        })
public final class Ow implements Runnable {

    public static final String VERSION = "1.0.0";

    /**
     * The API address.
     *
     * <p>Three sources in order, which is the usual precedence and the one
     * people expect: the flag beats the environment beats the default. The
     * default is the documented port rather than the one in {@code .env},
     * because preflight may have moved it and a CLI cannot read a compose
     * override — {@code make urls} is what knows, and the error message when
     * this is wrong says so.
     *
     * <p>{@code scope = INHERIT} so it can be given before or after the
     * subcommand. {@code ow --api ... alerts list} and
     * {@code ow alerts list --api ...} both work, because insisting on one
     * order is a thing to remember for no benefit.
     */
    @Option(names = { "--api" }, scope = ScopeType.INHERIT,
            paramLabel = "URL", defaultValue = "${env:OVERWATCH_API:-http://localhost:8080}",
            description = "Base URL of the fraud API. Default: ${DEFAULT-VALUE}")
    private String apiUrl;

    @Option(names = { "-q", "--quiet" }, scope = ScopeType.INHERIT,
            description = "Only the data — no headings, notes or hints.")
    private boolean quiet;

    @Spec
    private CommandLine.Model.CommandSpec spec;

    /** Built per invocation. One command runs per process, so there is nothing to cache. */
    public Api api() {
        return new Api(apiUrl);
    }

    public boolean quiet() {
        return quiet;
    }

    @Override
    public void run() {
        // No subcommand: print the help rather than doing something. A tool
        // that guesses what you meant with no arguments is a tool that
        // eventually guesses wrong on a destructive command.
        spec.commandLine().usage(System.out);
    }

    public static void main(String[] args) {
        Ow root = new Ow();
        CommandLine cli = new CommandLine(root)
                .setCaseInsensitiveEnumValuesAllowed(true)
                // An unknown option is a typo, and a typo on a command that
                // changes state must not be silently treated as an argument.
                .setUnmatchedArgumentsAllowed(false)
                .setColorScheme(CommandLine.Help.defaultColorScheme(
                        Ansi.enabled() ? CommandLine.Help.Ansi.ON
                                       : CommandLine.Help.Ansi.OFF))
                // A CliException carries a sentence for the user; anything else
                // is a bug in here and keeps its stack trace, because a bug
                // without one is a bug nobody can fix.
                .setExecutionExceptionHandler((e, commandLine, parseResult) -> {
                    if (e instanceof CliException) {
                        Out.fail(e.getMessage());
                        return 1;
                    }
                    throw e;
                });
        System.exit(cli.execute(args));
    }
}
