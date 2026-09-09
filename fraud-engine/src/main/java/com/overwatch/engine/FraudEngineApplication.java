package com.overwatch.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

/**
 * Consumes transactions, evaluates each against the configured rule set,
 * accumulates a risk score, and persists any resulting alerts.
 *
 * <p>This is the only service that writes alerts. Keeping detection separate from
 * the read API means the engine can be scaled to match ingest volume without
 * affecting anyone querying results, and a slow consumer never applies
 * back-pressure to the dashboard.
 *
 * <p>{@code @EntityScan} is required because the entities live in {@code common}:
 * this service writes the tables the API reads, and one shared mapping is the only
 * way those two views cannot drift. Component scanning already reaches the rules,
 * which sit under this package.
 */
@SpringBootApplication
// Boot 4 modularisation moved this out of ...autoconfigure.domain.
@EntityScan("com.overwatch.common.persistence")
public class FraudEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudEngineApplication.class, args);
    }
}
