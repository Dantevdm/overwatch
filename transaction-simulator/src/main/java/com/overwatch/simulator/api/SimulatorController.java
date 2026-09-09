package com.overwatch.simulator.api;

import com.overwatch.simulator.generate.FraudPattern;
import com.overwatch.simulator.service.SimulatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime control over the transaction stream.
 *
 * <p>This exists so a demonstration is directable rather than something you wait
 * for: pause the stream, slow it down to read individual alerts, or inject a
 * specific fraud pattern and watch that rule fire within a second.
 */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final SimulatorService simulator;

    public SimulatorController(SimulatorService simulator) {
        this.simulator = simulator;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", simulator.isRunning());
        body.put("transactionsPerSecond", simulator.getRatePerSecond());
        body.put("fraudInjectionRate", simulator.getFraudRate());
        body.put("published", simulator.getPublished());
        body.put("fraudPublished", simulator.getFraudPublished());
        body.put("availablePatterns", Arrays.stream(FraudPattern.values()).map(Enum::name).toList());
        return body;
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        simulator.setRunning(true);
        return status();
    }

    @PostMapping("/pause")
    public Map<String, Object> pause() {
        simulator.setRunning(false);
        return status();
    }

    /** Change throughput without a restart. Capped at 500/s to protect the demo. */
    @PostMapping("/rate")
    public Map<String, Object> rate(@RequestParam int perSecond) {
        simulator.setRatePerSecond(perSecond);
        return status();
    }

    @PostMapping("/fraud-rate")
    public ResponseEntity<Map<String, Object>> fraudRate(@RequestParam double rate) {
        if (rate < 0 || rate > 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "rate must be between 0 and 1, got " + rate));
        }
        simulator.setFraudRate(rate);
        return ResponseEntity.ok(status());
    }

    /**
     * Publish one specific fraud shape right now.
     *
     * <p>The demonstration move: {@code POST /api/simulator/inject/COMPOUND} puts a
     * large, round, foreign, small-hours crypto transaction on the stream, and a
     * CRITICAL alert with five contributing rules appears on the dashboard about a
     * second later.
     */
    @PostMapping("/inject/{pattern}")
    public ResponseEntity<Map<String, Object>> inject(@PathVariable String pattern) {
        FraudPattern parsed;
        try {
            parsed = FraudPattern.valueOf(pattern.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown pattern '" + pattern + "'",
                    "available", Arrays.stream(FraudPattern.values()).map(Enum::name).toList()));
        }
        int count = simulator.inject(parsed);
        return ResponseEntity.ok(Map.of(
                "injected", parsed.name(),
                "transactions", count));
    }
}
