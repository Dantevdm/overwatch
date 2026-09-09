package com.overwatch.api.web;

import com.overwatch.api.dto.Dtos.DashboardStats;
import com.overwatch.api.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
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
                    always rendered together.""")
    public DashboardStats dashboard() {
        return stats.dashboard();
    }
}
