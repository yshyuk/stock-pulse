package com.stockpulse.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal API surface for now.
 *
 * <p>The app is batch-style and normally exits right after a run, so this controller is only
 * reachable if the process is started manually for inspection. It exists to reserve the
 * {@code api} package for a future read-only report-lookup API.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "stock-pulse");
    }

    // Report lookup endpoints now live in ReportController (GET /api/reports/{date}, /latest).
}
