package com.overwatch.api.web;

import com.overwatch.api.dto.Dtos.RulePerformance;
import com.overwatch.api.dto.Dtos.RuleView;
import com.overwatch.api.repository.RuleRepository;
import com.overwatch.api.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rule configuration and operational statistics.
 *
 * <p>This is where "rules are data, not code" becomes visible: changing a
 * threshold or moving a rule into shadow is an API call, and the engine picks it
 * up on its next refresh without a redeploy.
 */
@RestController
@RequestMapping("/api/rules")
@Tag(name = "Rules", description = "Rule configuration, state and performance")
public class RuleController {

    private static final List<String> STATES = List.of("ENABLED", "DISABLED", "SHADOW");

    private final RuleRepository rules;
    private final StatsService stats;

    public RuleController(RuleRepository rules, StatsService stats) {
        this.rules = rules;
        this.stats = stats;
    }

    @GetMapping
    @Operation(summary = "All configured rules, heaviest first")
    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByOrderByWeightDesc().stream().map(RuleView::from).toList();
    }

    @GetMapping("/performance")
    @Operation(summary = "Per-rule operational metrics",
            description = """
                    Fire count, share of alerts, analyst disposition and shadow hits.
                    falsePositiveRate is null until alerts have been reviewed — an
                    unreviewed rule reporting 0% would read as a perfect rule.""")
    public List<RulePerformance> performance() {
        return stats.rulePerformance();
    }

    @PatchMapping("/{id}/state")
    @Operation(summary = "Enable, disable, or move a rule into shadow",
            description = """
                    SHADOW evaluates the rule against live traffic and records what it
                    would have flagged, without raising alerts. That is how a rule is
                    tuned before it is trusted.""")
    @Transactional
    public ResponseEntity<?> setState(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String state = body.getOrDefault("state", "").trim().toUpperCase(Locale.ROOT);
        if (!STATES.contains(state)) {
            return ResponseEntity.badRequest().body(Map.of("error", "state must be one of " + STATES));
        }
        if (rules.updateState(id, state) == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", id, "state", state,
                "note", "the engine applies this on its next refresh cycle"));
    }

    @PatchMapping("/{id}/weight")
    @Operation(summary = "Change a rule's contribution to the risk score")
    @Transactional
    public ResponseEntity<?> setWeight(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BigDecimal weight;
        try {
            weight = new BigDecimal(String.valueOf(body.get("weight")));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "weight must be a number"));
        }
        if (weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "weight must be between 0 and 1"));
        }
        if (rules.updateWeight(id, weight) == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", id, "weight", weight));
    }
}
