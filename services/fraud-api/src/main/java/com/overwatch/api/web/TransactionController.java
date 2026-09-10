package com.overwatch.api.web;

import com.overwatch.api.dto.Dtos.TransactionView;
import com.overwatch.api.repository.TransactionReadRepository;
import com.overwatch.common.persistence.TransactionEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@Tag(name = "Transactions", description = "The observed transaction stream")
public class TransactionController {

    private final TransactionReadRepository transactions;

    public TransactionController(TransactionReadRepository transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    @Operation(summary = "List transactions, newest first",
            description = """
                    Filters combine: every one supplied has to match.

                    `customerId` is exact and is what the cardholder screens link
                    on. `customer` is a case-insensitive substring of the display
                    name, which is what a person types — substring rather than
                    prefix, because people search for a surname.""")
    @Transactional(readOnly = true)
    public Map<String, Object> list(
            @RequestParam(required = false) String cardId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false)
            @Parameter(description = "Exact cardholder reference, e.g. cust-00042.")
            String customerId,
            @RequestParam(required = false)
            @Parameter(description = "Any part of a cardholder's name, case-insensitively.")
            String customer,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Rows per page, 1-200. Ten by default, matching the UI.")
            @RequestParam(defaultValue = "10") int size) {

        // EPOCH rather than null for "no window": the query compares against this
        // unconditionally, because a nullable timestamp parameter is one Postgres
        // cannot infer a type for. See TransactionReadRepository#search.
        Instant since = hours > 0 ? Instant.now().minus(hours, ChronoUnit.HOURS) : Instant.EPOCH;
        Page<TransactionEntity> found = transactions.search(
                blankToNull(cardId), blankToNull(category),
                blankToNull(customerId), likePattern(customer), since,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));

        return Map.of(
                "content", found.getContent().stream().map(TransactionView::from).toList(),
                "page", found.getNumber(),
                "size", found.getSize(),
                "totalElements", found.getTotalElements(),
                "totalPages", found.getTotalPages());
    }

    @GetMapping("/categories")
    @Operation(summary = "Merchant categories present in the data, most common first",
            description = """
                    What the category filter offers. Derived from the transactions
                    themselves rather than from a fixed list, so a category can
                    never appear in the filter with no rows behind it.""")
    @Transactional(readOnly = true)
    public List<String> categories() {
        return transactions.countByCategory().stream()
                .map(row -> (String) row[0])
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<TransactionView> get(@PathVariable UUID id) {
        return transactions.findById(id)
                .map(TransactionView::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * A LIKE pattern for the cardholder-name filter, or {@code %} for no filter.
     *
     * <p>Built here rather than in the query for the reason set out on
     * {@link TransactionReadRepository#searchCustomers}: a parameter that appears
     * only inside {@code CONCAT} has no type the planner can infer, and Postgres
     * resolves it to {@code bytea}. The user's own wildcards are escaped, so a
     * typed underscore searches for an underscore.
     */
    private static String likePattern(String s) {
        if (s == null || s.isBlank()) return "%";
        return "%" + s.trim().toLowerCase()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
