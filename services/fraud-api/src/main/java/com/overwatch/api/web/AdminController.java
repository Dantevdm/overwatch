package com.overwatch.api.web;

import com.overwatch.api.service.DataResetService;
import com.overwatch.api.simulator.SimulatorGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo operations. Currently one: empty the store so a run can start from zero.
 *
 * <p>This endpoint destroys data and there is no authentication anywhere in the
 * system — a combination worth being deliberate about rather than shipping on the
 * grounds that it is only a demo. So it is behind a flag,
 * {@code overwatch.api.allow-reset}, and answers 403 when that is off. It ships
 * on because the stack exists to be demonstrated and a reset button that needs a
 * configuration change to work is a reset button nobody has.
 *
 * <p>The flag is the honest seam: the same property that turns this on here is
 * the one a real deployment sets to false, and it is one line rather than a code
 * change. When authentication does arrive at the BFF — noted as a known gap —
 * this is the first route that should require a role.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Demo operations — clearing observed data")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final DataResetService reset;
    private final SimulatorGateway simulator;
    private final boolean allowReset;

    public AdminController(DataResetService reset,
                           SimulatorGateway simulator,
                           @Value("${overwatch.api.allow-reset:true}") boolean allowReset) {
        this.reset = reset;
        this.simulator = simulator;
        this.allowReset = allowReset;
        if (!allowReset) {
            log.info("Data reset is disabled (overwatch.api.allow-reset=false)");
        }
    }

    @PostMapping("/reset")
    @Operation(summary = "Clear observed data — transactions, alerts and rule hits",
            description = """
                    Empties the store so a demonstration can start from nothing, and
                    zeroes the simulator's session counters so its screen agrees.

                    Rule configuration is deliberately kept: rules are configuration
                    rather than history, they are seeded by a Flyway migration that
                    will not re-run on an existing volume, and a threshold you have
                    just tuned should survive clearing the traffic you tuned it
                    against.

                    Prometheus counters are also kept, because they are monotonic by
                    contract — so straight after a reset the dashboard reads zero
                    while Grafana still shows the whole run. Both are correct; they
                    answer different questions.

                    Returns the row counts removed. Answers 403 when
                    `overwatch.api.allow-reset` is false.""")
    public ResponseEntity<Map<String, Object>> resetData() {
        if (!allowReset) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Data reset is disabled on this instance.",
                    "detail", "Set overwatch.api.allow-reset=true to enable it."));
        }

        Map<String, Long> removed = reset.reset();

        // Best effort, and deliberately after the truncate. The database is the
        // thing that matters; the simulator's counters are cosmetic, and if it is
        // down or mid-restart that must not turn a successful reset into a 5xx
        // and leave the caller unsure whether the data was cleared.
        boolean countersReset = true;
        try {
            simulator.post("/api/simulator/reset-counters");
        } catch (RuntimeException e) {
            countersReset = false;
            log.warn("Cleared the store, but could not reset the simulator's counters: {}",
                    e.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removed", removed);
        body.put("rulesKept", true);
        body.put("simulatorCountersReset", countersReset);
        body.put("note", "Prometheus counters are monotonic and were not reset, "
                + "so Grafana still shows the full run.");
        return ResponseEntity.ok(body);
    }
}
