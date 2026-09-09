package com.overwatch.simulator.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Simulator tuning, bound from {@code overwatch.simulator.*}.
 *
 * @param enabled              whether to produce on startup
 * @param transactionsPerSecond nominal rate
 * @param fraudInjectionRate   share of traffic deliberately shaped to trip a rule.
 *                             Real card fraud sits well under 1%; the default here
 *                             is far higher so the dashboard has something to show
 *                             within seconds rather than hours.
 * @param cardPoolSize         distinct cards in circulation. Small enough that
 *                             velocity is reachable, large enough that ordinary
 *                             traffic does not trip it by accident.
 */
@ConfigurationProperties(prefix = "overwatch.simulator")
public record SimulatorProperties(
        boolean enabled,
        int transactionsPerSecond,
        double fraudInjectionRate,
        int cardPoolSize
) {
    public SimulatorProperties {
        if (transactionsPerSecond <= 0) transactionsPerSecond = 5;
        if (fraudInjectionRate < 0 || fraudInjectionRate > 1) fraudInjectionRate = 0.08;
        if (cardPoolSize <= 0) cardPoolSize = 2000;
    }
}
