package com.overwatch.common;

/**
 * Kafka topic names, defined once so producers and consumers cannot drift apart.
 */
public final class Topics {

    /** Raw transaction events: simulator -> fraud engine. */
    public static final String TRANSACTIONS = "transactions";

    /** Alerts raised by the engine, for downstream notification consumers. */
    public static final String FRAUD_ALERTS = "fraud-alerts";

    /**
     * Transactions the engine could not process, with the failure attached.
     *
     * <p>The {@code .DLT} suffix is Spring Kafka's own default, and it is kept
     * rather than invented: anyone who has operated a Spring Kafka consumer
     * already knows what a topic ending in {@code .DLT} is, and matching the
     * framework's convention means the recoverer needs no destination resolver
     * to find it.
     */
    public static final String TRANSACTIONS_DLT = TRANSACTIONS + ".DLT";

    private Topics() {
    }
}
