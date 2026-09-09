package com.overwatch.api.web;

import com.overwatch.api.dto.Dtos.ReplayRequest;
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

    @GetMapping("/rule-types")
    @Operation(summary = "Rule types available to replay")
    public Map<String, Object> ruleTypes() {
        return Map.of("ruleTypes", replay.availableRuleTypes());
    }
}
