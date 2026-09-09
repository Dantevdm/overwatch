package com.overwatch.api.web;

import com.overwatch.api.dto.Dtos.AlertView;
import com.overwatch.api.repository.AlertRepository;
import com.overwatch.common.persistence.FraudAlertEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/alerts")
@Tag(name = "Alerts", description = "Fraud alerts and analyst disposition")
public class AlertController {

    private static final List<String> STATUSES =
            List.of("OPEN", "REVIEWING", "CONFIRMED", "CLEARED");

    private final AlertRepository alerts;

    public AlertController(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    @Operation(summary = "List alerts, newest first")
    @Transactional(readOnly = true)
    public Map<String, Object> list(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "168") int hours,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        Instant since = hours > 0 ? Instant.now().minus(hours, ChronoUnit.HOURS) : null;
        Page<FraudAlertEntity> found = alerts.search(
                upper(severity), upper(status), since,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));

        return Map.of(
                "content", found.getContent().stream().map(AlertView::summary).toList(),
                "page", found.getNumber(),
                "size", found.getSize(),
                "totalElements", found.getTotalElements(),
                "totalPages", found.getTotalPages());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One alert with every contributing rule")
    @Transactional(readOnly = true)
    public ResponseEntity<AlertView> get(@PathVariable UUID id) {
        // Detail includes the hits: an alert without its reasons tells an analyst
        // something is wrong without saying what.
        return alerts.findByIdWithHits(id)
                .map(AlertView::detailed)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Record an analyst decision",
            description = "CONFIRMED and CLEARED are what make a false-positive rate computable.")
    @Transactional
    public ResponseEntity<?> updateStatus(@PathVariable UUID id,
                                          @RequestBody Map<String, String> body) {
        String status = upper(body.get("status"));
        if (status == null || !STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "status must be one of " + STATUSES));
        }
        return alerts.findById(id).map(alert -> {
            alert.setStatus(status);
            if ("CONFIRMED".equals(status) || "CLEARED".equals(status)) {
                alert.setResolvedAt(Instant.now());
            }
            alerts.save(alert);
            return ResponseEntity.ok((Object) AlertView.summary(alert));
        }).orElse(ResponseEntity.notFound().build());
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
