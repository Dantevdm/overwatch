package com.overwatch.api.web;

import com.overwatch.api.dto.Dtos.TransactionView;
import com.overwatch.api.repository.TransactionReadRepository;
import com.overwatch.common.persistence.TransactionEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
    @Operation(summary = "List transactions, newest first")
    @Transactional(readOnly = true)
    public Map<String, Object> list(
            @RequestParam(required = false) String cardId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        // EPOCH rather than null for "no window": the query compares against this
        // unconditionally, because a nullable timestamp parameter is one Postgres
        // cannot infer a type for. See TransactionReadRepository#search.
        Instant since = hours > 0 ? Instant.now().minus(hours, ChronoUnit.HOURS) : Instant.EPOCH;
        Page<TransactionEntity> found = transactions.search(
                blankToNull(cardId), blankToNull(category), since,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));

        return Map.of(
                "content", found.getContent().stream().map(TransactionView::from).toList(),
                "page", found.getNumber(),
                "size", found.getSize(),
                "totalElements", found.getTotalElements(),
                "totalPages", found.getTotalPages());
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
}
