package com.overwatch.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * Backend-for-frontend over the fraud data store: transactions, alerts, rule
 * configuration and the aggregated statistics the dashboard renders.
 *
 * <p>Read-mostly by design. The two meaningful write paths are analyst disposition
 * of alerts and rule configuration — the latter being what makes thresholds
 * tunable without a redeploy.
 *
 * <p>Both scan attributes are load-bearing. Spring Boot scans from this class's
 * own package by default, and neither the shared entities nor the rule
 * implementations live under it: entities are in {@code common} because the engine
 * writes what this service reads, and the rules are a library because the replay
 * endpoint has to run the real implementations rather than a copy of them. Without
 * these, JPA reports the entities as unmanaged types at startup and replay finds
 * no rules to run.
 */
@SpringBootApplication(scanBasePackages = {
        "com.overwatch.api",
        "com.overwatch.engine.rule"
})
@EntityScan("com.overwatch.common.persistence")
public class FraudApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudApiApplication.class, args);
    }
}
