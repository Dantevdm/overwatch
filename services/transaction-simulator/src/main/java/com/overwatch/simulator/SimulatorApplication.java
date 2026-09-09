package com.overwatch.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Generates a continuous stream of realistic South African card transactions and
 * publishes them to Kafka for the fraud engine to evaluate.
 *
 * <p>Rate and fraud-injection ratio are runtime-configurable so the stream can be
 * driven hard for a load demonstration or slowed to a trickle for a walkthrough.
 */
@SpringBootApplication
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
