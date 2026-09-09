package com.overwatch.api.simulator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Runtime control over the transaction stream, proxied to the simulator.
 *
 * <p>The point of putting these controls in front of a reviewer is that the
 * pipeline stops being something you wait for. Drop the rate to one per second
 * and every alert on the dashboard is one you can follow; inject a COMPOUND
 * pattern and a CRITICAL alert with five contributing rules appears about a
 * second later, which is the whole system demonstrated in one click.
 */
@RestController
@RequestMapping("/api/simulator")
@Tag(name = "Simulator", description = "Drive the transaction stream at runtime")
public class SimulatorController {

    private final SimulatorGateway simulator;

    public SimulatorController(SimulatorGateway simulator) {
        this.simulator = simulator;
    }

    @GetMapping("/status")
    @Operation(summary = "Run state, throughput, fraud share and published counters")
    public Map<String, Object> status() {
        return simulator.get("/api/simulator/status");
    }

    @PostMapping("/start")
    @Operation(summary = "Resume publishing")
    public Map<String, Object> start() {
        return simulator.post("/api/simulator/start");
    }

    @PostMapping("/pause")
    @Operation(summary = "Stop publishing without stopping the service")
    public Map<String, Object> pause() {
        return simulator.post("/api/simulator/pause");
    }

    @PostMapping("/rate")
    @Operation(summary = "Change throughput, in transactions per second")
    public Map<String, Object> rate(@RequestParam int perSecond) {
        return simulator.post("/api/simulator/rate?perSecond=" + perSecond);
    }

    @PostMapping("/fraud-rate")
    @Operation(summary = "Change the share of traffic shaped to trip a rule (0–1)")
    public Map<String, Object> fraudRate(@RequestParam double rate) {
        return simulator.post("/api/simulator/fraud-rate?rate=" + rate);
    }

    @PostMapping("/inject/{pattern}")
    @Operation(summary = "Publish one specific fraud pattern immediately")
    public Map<String, Object> inject(@PathVariable String pattern) {
        // Strip anything that is not a bare enum name before it becomes part of a
        // downstream URL. The simulator validates the name itself and answers 400
        // for one it does not know; this only stops a crafted value reshaping the
        // request path on the way there.
        String safe = pattern.replaceAll("[^A-Za-z0-9_]", "");
        return simulator.post("/api/simulator/inject/" + safe);
    }
}
