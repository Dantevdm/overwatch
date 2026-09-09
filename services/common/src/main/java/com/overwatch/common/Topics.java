package com.overwatch.common;

/**
 * Kafka topic names, defined once so producers and consumers cannot drift apart.
 */
public final class Topics {

    /** Raw transaction events: simulator -> fraud engine. */
    public static final String TRANSACTIONS = "transactions";

    /** Alerts raised by the engine, for downstream notification consumers. */
    public static final String FRAUD_ALERTS = "fraud-alerts";

    private Topics() {
    }
}
