package com.overwatch.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend-for-frontend over the fraud data store: transactions, alerts, rule
 * configuration and the aggregated statistics the dashboard renders.
 *
 * <p>Read-mostly by design. The one meaningful write path is rule configuration,
 * which is what makes thresholds tunable without a redeploy.
 */
@SpringBootApplication
public class FraudApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudApiApplication.class, args);
    }
}
