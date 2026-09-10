package com.overwatch.api.web;

import com.overwatch.api.dto.Dtos.ReplayRequest;
import com.overwatch.api.dto.Dtos.SweepRequest;
import com.overwatch.api.dto.Dtos.ReplayResult;
import com.overwatch.api.service.ReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/replay")
@Tag(name = "Replay", description = "What-if analysis against stored history")
public class ReplayController {

    private final ReplayService replay;

    public ReplayController(ReplayService replay) {
        this.replay = replay;
    }

    @PostMapping
    @Operation(summary = "Replay stored transactions through a candidate rule configuration",
            description = """
                    Reports the alerts a configuration *would* have produced. Nothing is
                    written and no rule is changed, so "should we drop the high-value
                    threshold to R30 000?" becomes a measurement rather than an argument.

                    Rules that depend on card history (VELOCITY, AMOUNT_DEVIATION) report
                    nothing here rather than producing a misleading answer from a
                    baseline that no longer reflects the window being replayed.""")
    public ResponseEntity<?> replay(@RequestBody ReplayRequest request) {
        try {
            ReplayResult result = replay.replay(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sweep")
    @Operation(summary = "Replay many candidate values for one parameter in a single pass",
            description = """
                    Replay answers "what would this threshold have caught". This
                    answers "where should this threshold sit", which is the
                    question actually being asked -- and it answers it with a
                    curve rather than one number at a time.

                    One pass over the history, every candidate evaluated against
                    each transaction as it is read. Sweeping seven thresholds
                    therefore costs one trip through the data rather than seven.

                    Nothing is written and no rule is changed. Up to 25 candidate
                    values, and the same seven-day window cap replay applies.""")
    public ResponseEntity<?> sweep(@RequestBody SweepRequest request) {
        try {
            return ResponseEntity.ok(replay.sweep(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/rule-types")
    @Operation(summary = "Rule types available to replay")
    public Map<String, Object> ruleTypes() {
        return Map.of("ruleTypes", replay.availableRuleTypes());
    }

    @GetMapping("/sweepable")
    @Operation(summary = "What each rule can be swept on, and a ladder to start from",
            description = """
                    Includes the rules that cannot be swept, each with the reason
                    -- a country list is not a threshold, and a rule that counts a
                    card's recent history has nothing to count during a replay.
                    Saying so is more use than omitting them.""")
    public Map<String, Object> sweepable() {
        return Map.of("sweepable", replay.sweepable());
    }
}
