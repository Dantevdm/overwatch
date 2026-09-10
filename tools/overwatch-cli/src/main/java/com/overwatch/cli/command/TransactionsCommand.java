package com.overwatch.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.overwatch.cli.Ansi;
import com.overwatch.cli.Api;
import com.overwatch.cli.Out;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Locale;

/**
 * {@code ow txn list} — the raw stream.
 *
 * <p>Aliased to {@code transactions} as well, because the long form is what you
 * guess and the short form is what you type the second time.
 */
@Command(name = "txn", aliases = { "transactions" },
        header = "The transaction stream, newest first.",
        subcommands = { TransactionsCommand.ListCommand.class })
public class TransactionsCommand extends Base {

    @Override
    public Integer call() {
        return usage();
    }

    @Command(name = "list",
            header = "Recent transactions, with an optional filter.",
            description = {
                    "",
                    "The customer filter matches a name or a cardholder reference, the same",
                    "way the web console's search box does.",
                    ""
            })
    public static class ListCommand extends Base {

        @Option(names = { "-c", "--customer" }, paramLabel = "TEXT",
                description = "Cardholder name or reference, partial match.")
        private String customer;

        @Option(names = { "--category" }, paramLabel = "CATEGORY",
                description = "Merchant category, e.g. groceries, crypto.")
        private String category;

        @Option(names = { "-n", "--limit" }, paramLabel = "N", defaultValue = "10",
                description = "Rows to show. Default: ${DEFAULT-VALUE}")
        private int limit;

        @Option(names = { "-p", "--page" }, paramLabel = "N", defaultValue = "0",
                description = "Page, zero-based. Default: ${DEFAULT-VALUE}")
        private int page;

        @Override
        public Integer call() {
            JsonNode response = api().get("/api/transactions", Api.body(
                    "size", limit, "page", page,
                    "customer", blankToNull(customer),
                    "category", blankToNull(category)));

            JsonNode rows = Api.at(response, "content");
            if (!rows.isArray() || rows.isEmpty()) {
                Out.note("No transactions match.");
                return 0;
            }

            Out.Table table = new Out.Table()
                    .column("when")
                    .column("amount", Out.Align.RIGHT)
                    .column("cardholder")
                    .column("merchant")
                    .column("cat")
                    .column("cc");
            for (JsonNode txn : rows) {
                // Country is coloured only when it is not ZA. On a South
                // African stream the foreign ones are the interesting rows, and
                // colouring all of them would colour nothing.
                String country = Api.text(txn, "countryCode", "—");
                table.row(
                        Out.dateTime(Api.text(txn, "occurredAt", null)),
                        Out.zar(Api.number(txn, "amount")),
                        Out.clip(Api.text(txn, "customerName", "—"), 22),
                        Out.clip(Api.text(txn, "merchantName", "—"), 30),
                        Ansi.dim(Out.clip(Api.text(txn, "merchantCategory", "—"), 12)),
                        "ZA".equals(country) ? Ansi.dim(country) : Ansi.yellow(country));
            }
            table.print();

            if (!quiet()) {
                long total = Api.integer(response, "totalElements");
                Out.line();
                Out.note(Out.count(rows.size()) + " of " + Out.count(total) + " transactions");
            }
            return 0;
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
