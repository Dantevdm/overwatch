package com.overwatch.api.web;

import com.overwatch.api.dto.Dtos.DashboardStats;
import com.overwatch.api.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
@Tag(name = "Statistics", description = "Aggregations behind the dashboard")
public class StatsController {

    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Everything the dashboard's landing page needs, in one call",
            description = """
                    Deliberately one request rather than six. The dashboard polls, and
                    six polling endpoints is six times the load for a view that is
                    always rendered together.

                    `rangeMinutes` sets the window the two time series cover, from 5
                    minutes to 7 days; it does not affect the headline totals, which
                    carry their own fixed periods. Bucket width is derived from the
                    range and returned as `bucketSeconds`, so the client can label an
                    axis without duplicating the choice. Out-of-range values are
                    clamped rather than rejected, and the applied value comes back as
                    `rangeMinutes`.""")
    public DashboardStats dashboard(
            @RequestParam(required = false)
            @Parameter(description = "Window for the time series, in minutes. "
                    + "Defaults to 1440 (24h); capped at 10080 (7 days).")
            Integer rangeMinutes) {
        return stats.dashboard(StatsService.clampRange(rangeMinutes));
    }
}
