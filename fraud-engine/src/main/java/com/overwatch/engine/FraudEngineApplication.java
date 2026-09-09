package com.overwatch.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Consumes transactions, evaluates each against the configured rule set, accumulates
 * a risk score, and persists any resulting alerts.
 *
 * <p>This is the only service that writes alerts. Keeping detection separate from the
 * read API means the engine can be scaled to match ingest volume without affecting
 * anyone querying results, and a slow consumer never applies back-pressure to the
 * dashboard.
 */
@SpringBootApplication
public class FraudEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudEngineApplication.class, args);
    }
}
