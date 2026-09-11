package com.overwatch.cli.command;

import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.CliException;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code ow report} — save the dashboard as a file.
 *
 * <p>The reason this exists alongside the button in the console: a report that
 * can only be produced by a person clicking is a report nobody has at 07:00 on a
 * Monday. This is a single command with an exit code, so it can be put in cron
 * and the file mailed on by whatever already does that.
 */
@Command(name = "report",
        header = "Save the fraud report as a workbook or a document.",
        description = """
                Writes the same report the console offers, built by the API from the
                same figures the dashboard reads.

                  ow report pdf                 the last 24 hours, into the working directory
                  ow report xlsx --range 7d     a week, as a workbook
                  ow report pdf -o /tmp/it.pdf  somewhere specific""")
public class ReportCommand extends Base {

    /** What the CLI will accept as a window, and the minutes each one means. */
    private static final Map<String, Integer> RANGES = new LinkedHashMap<>();

    static {
        RANGES.put("5m", 5);
        RANGES.put("15m", 15);
        RANGES.put("30m", 30);
        RANGES.put("1h", 60);
        RANGES.put("12h", 720);
        RANGES.put("24h", 1440);
        RANGES.put("7d", 10080);
    }

    @Parameters(index = "0", paramLabel = "FORMAT",
            description = "pdf or xlsx.")
    private String format;

    @Option(names = {"-r", "--range"}, paramLabel = "WINDOW",
            description = "Window the report covers: ${COMPLETION-CANDIDATES}. Default 24h.",
            completionCandidates = Ranges.class)
    private String range = "24h";

    @Option(names = {"-o", "--out"}, paramLabel = "PATH",
            description = "Where to write it. Defaults to the name the API suggests, "
                    + "which carries the generation time.")
    private Path out;

    @Option(names = "--force", description = "Overwrite the file if it already exists.")
    private boolean force;

    @Override
    public Integer call() {
        String extension = format.toLowerCase(Locale.ROOT);
        if (!"pdf".equals(extension) && !"xlsx".equals(extension)) {
            throw new CliException("Format must be pdf or xlsx, not '" + format + "'.");
        }
        Integer minutes = RANGES.get(range.toLowerCase(Locale.ROOT));
        if (minutes == null) {
            throw new CliException("Unknown window '" + range + "'. One of: "
                    + String.join(", ", RANGES.keySet()) + ".");
        }

        Api.Download download = api().download(
                "/api/reports/fraud-summary." + extension, Map.of("rangeMinutes", minutes));
        byte[] bytes = download.bytes();
        Path target = target(download, extension);
        write(target, bytes);

        if (!quiet()) {
            Out.ok("Saved " + Ansi.bold(target.toString())
                    + Ansi.dim("  (" + Out.count(bytes.length) + " bytes, "
                    + range + ")"));
        } else {
            // Quiet mode prints the path and nothing else, so the command can be
            // used as `mail -a "$(ow report pdf -q)"`.
            Out.line(target.toString());
        }
        return 0;
    }

    private Path target(Api.Download download, String extension) {
        if (out != null) {
            return out;
        }
        String name = download.suggestedName() != null
                ? download.suggestedName()
                : "overwatch-fraud-report." + extension;
        return Path.of(name);
    }

    private void write(Path target, byte[] bytes) {
        if (Files.exists(target) && !force) {
            throw new CliException(target + " already exists. Pass --force to overwrite it.");
        }
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new CliException("Could not write " + target + ": " + e.getMessage());
        }
    }

    /** Completion candidates for --range, so tab completion offers the real windows. */
    static class Ranges extends java.util.ArrayList<String> {
        Ranges() {
            super(RANGES.keySet());
        }
    }
}
