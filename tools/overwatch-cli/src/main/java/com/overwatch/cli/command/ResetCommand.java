package com.overwatch.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.CliException;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;

/**
 * {@code ow reset} — empty the store so a demonstration can start from nothing.
 *
 * <p>This destroys data, and the API behind it has no authentication, so the
 * confirmation here is not a formality. It asks you to type the word rather
 * than to press y: a single keystroke is exactly what gets pressed by accident,
 * and the web console's version of this button once took 171 911 transactions
 * because the destructive action was the focused default.
 *
 * <p>{@code --yes} skips the prompt, because a demo script that cannot reset
 * without a human is not a script. That is a deliberate opt-in, and it is the
 * only way past the prompt — there is no environment variable that quietly
 * disables it.
 */
@Command(name = "reset",
        header = "Clear transactions, alerts and rule hits. Destructive.",
        description = {
                "",
                "Rule configuration is kept: rules are configuration rather than history, and a",
                "threshold you have just tuned should survive clearing the traffic you tuned it",
                "against.",
                "",
                "Prometheus is left alone unless --metrics. Straight after a reset the console",
                "reads zero while Grafana still shows the whole run, and both are correct — they",
                "are separate decisions, so this makes you take them separately.",
                ""
        })
public class ResetCommand extends Base {

    @Option(names = { "-y", "--yes" },
            description = "Skip the confirmation. For scripts.")
    private boolean yes;

    @Option(names = { "--metrics" },
            description = "Also delete this stack's own series from Prometheus.")
    private boolean metrics;

    @Override
    public Integer call() {
        if (!yes && !confirmed()) {
            Out.note("Nothing was cleared.");
            return 1;
        }

        JsonNode result = api().post("/api/admin/reset", Api.body("metrics", metrics));

        JsonNode removed = Api.at(result, "removed");
        Out.ok("Cleared.");
        removed.properties().forEach(e ->
                Out.line("  " + Ansi.pad(e.getKey(), 20)
                        + Ansi.padLeft(Out.count(e.getValue().asLong()), 12)));

        if (!Api.at(result, "simulatorCountersReset").asBoolean(true)) {
            Out.warn("The store is empty, but the simulator's counters could not be "
                    + "reset — its screen will still show the old totals.");
        }

        String note = Api.text(result, "note", null);
        if (note != null && !quiet()) {
            Out.line();
            Out.note(note);
        }
        return 0;
    }

    /**
     * Ask for the word "clear".
     *
     * <p>Without a terminal there is nobody to ask, and assuming consent from a
     * pipe is how a destructive command ends up running in CI. It refuses and
     * names the flag instead.
     */
    private boolean confirmed() {
        Console console = System.console();
        if (console == null) {
            throw new CliException(
                    "Refusing to clear the store without a terminal to confirm at. "
                            + "Pass --yes if you mean it.");
        }
        Out.warn("This deletes every transaction, alert and rule hit at "
                + api().baseUrl() + ".");
        if (metrics) {
            Out.warn("It will also delete this stack's series from Prometheus.");
        }
        String answer = console.readLine("Type %s to continue: ", Ansi.bold("clear"));
        return "clear".equalsIgnoreCase(answer == null ? "" : answer.trim());
    }
}
