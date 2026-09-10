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
 * @param cardPoolSize         distinct cards in circulation. This is the sizing
 *                             knob for the velocity rule: velocity is a property
 *                             of a card, so this against the rate is what decides
 *                             whether that rule discriminates or fires on
 *                             everything.
 * @param seedHistory          publish a backfill of past spending on startup.
 * @param historyDays          how far back the backfill reaches.
 * @param historyPerCardPerDay transactions per card per day in the backfill. Held
 *                             near what a real card actually does, because the
 *                             backfill is what the amount-deviation rule builds
 *                             its per-card baseline from — an inflated figure
 *                             would make every baseline wrong in the same
 *                             direction.
 * @param historySeed          fixed seed for the backfill. Fixed on purpose: the
 *                             same seed produces the same transaction ids, so a
 *                             restart republishes the identical messages and the
 *                             engine's idempotency guard recognises every one of
 *                             them as a redelivery. Seeding is therefore safe to
 *                             repeat, which is the only way it can be safe at all
 *                             in a service that does not own a database and cannot
 *                             ask whether it has already run.
 */
@ConfigurationProperties(prefix = "overwatch.simulator")
public record SimulatorProperties(
        boolean enabled,
        int transactionsPerSecond,
        double fraudInjectionRate,
        int cardPoolSize,
        boolean seedHistory,
        int historyDays,
        double historyPerCardPerDay,
        long historySeed
) {
    public SimulatorProperties {
        if (transactionsPerSecond <= 0) transactionsPerSecond = 5;
        if (fraudInjectionRate < 0 || fraudInjectionRate > 1) fraudInjectionRate = 0.08;
        if (cardPoolSize <= 0) cardPoolSize = 2000;
        if (historyDays <= 0) historyDays = 30;
        // Capped as well as floored. The backfill is cardPoolSize × days × this,
        // which reaches six figures quickly, and a typo in a config file should
        // not be able to publish ten million messages before anyone notices.
        if (historyPerCardPerDay <= 0 || historyPerCardPerDay > 20) historyPerCardPerDay = 1.4;
        if (historySeed == 0) historySeed = 20260910L;
    }
}
