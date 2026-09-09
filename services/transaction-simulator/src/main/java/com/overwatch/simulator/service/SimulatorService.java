package com.overwatch.simulator.service;

import com.overwatch.common.Topics;
import com.overwatch.common.domain.Transaction;
import com.overwatch.simulator.generate.FraudPattern;
import com.overwatch.simulator.generate.TransactionGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes generated transactions to the broker on a fixed tick.
 *
 * <p>Rate and fraud share are mutable at runtime through the control API, so a
 * demonstration can be slowed to a trickle for a walkthrough or driven hard to show
 * the pipeline under load, without a restart.
 */
@Service
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorService {

    private static final Logger log = LoggerFactory.getLogger(SimulatorService.class);
    private static final long TICK_MS = 1000;

    private final KafkaTemplate<String, Object> kafka;
    private final TransactionGenerator generator;
    private final MeterRegistry meters;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger ratePerSecond = new AtomicInteger();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong fraudPublished = new AtomicLong();
    private volatile double fraudRate;

    public SimulatorService(KafkaTemplate<String, Object> kafka,
                            SimulatorProperties properties,
                            MeterRegistry meters) {
        this.kafka = kafka;
        this.meters = meters;
        this.generator = new TransactionGenerator(properties.cardPoolSize(), new Random());
        this.running.set(properties.enabled());
        this.ratePerSecond.set(properties.transactionsPerSecond());
        this.fraudRate = properties.fraudInjectionRate();

        meters.gauge("simulator.rate", ratePerSecond);
        log.info("Simulator initialised — {} txn/s, {}% fraud injection, {} cards, running={}",
                ratePerSecond.get(), Math.round(fraudRate * 100),
                properties.cardPoolSize(), running.get());
    }

    @Scheduled(fixedRate = TICK_MS)
    public void tick() {
        if (!running.get()) {
            return;
        }
        int budget = ratePerSecond.get();
        for (int i = 0; i < budget; i++) {
            if (ThreadLocalRandom.current().nextDouble() < fraudRate) {
                // A burst counts as one draw but publishes several transactions;
                // that is what makes velocity reachable at all.
                emit(generator.fraudulent(randomPattern()), true);
            } else {
                emit(List.of(generator.normal()), false);
            }
        }
    }

    /** Publish a specific pattern immediately, for demonstrations. */
    public int inject(FraudPattern pattern) {
        List<Transaction> batch = generator.fraudulent(pattern);
        emit(batch, true);
        log.info("Injected {} — {} transaction(s)", pattern, batch.size());
        return batch.size();
    }

    private void emit(List<Transaction> batch, boolean fraudulent) {
        for (Transaction txn : batch) {
            // Key by card so every transaction for a card lands on one partition
            // and stays ordered. Velocity depends on that ordering.
            kafka.send(Topics.TRANSACTIONS, txn.cardId(), txn);
            published.incrementAndGet();
            meters.counter("simulator.published",
                    "category", txn.merchantCategory()).increment();
        }
        if (fraudulent) {
            fraudPublished.addAndGet(batch.size());
        }
    }

    private static FraudPattern randomPattern() {
        FraudPattern[] all = FraudPattern.values();
        return all[ThreadLocalRandom.current().nextInt(all.length)];
    }

    // ---- control -----------------------------------------------------------

    public boolean isRunning() {
        return running.get();
    }

    public void setRunning(boolean value) {
        running.set(value);
        log.info("Simulator {}", value ? "started" : "paused");
    }

    public int getRatePerSecond() {
        return ratePerSecond.get();
    }

    public void setRatePerSecond(int value) {
        ratePerSecond.set(Math.max(0, Math.min(value, 500)));
    }

    public double getFraudRate() {
        return fraudRate;
    }

    public void setFraudRate(double value) {
        this.fraudRate = Math.max(0, Math.min(value, 1));
    }

    public long getPublished() {
        return published.get();
    }

    public long getFraudPublished() {
        return fraudPublished.get();
    }

    /**
     * Zero the session counters, for a demo starting from a clean slate.
     *
     * <p>Only these two. The Micrometer counters behind Prometheus are
     * deliberately left alone: a Prometheus counter is monotonic by contract, and
     * `rate()` reads any decrease as a process restart. Resetting them would put
     * a false spike into every Grafana panel and lose the history the dashboards
     * exist to show. These two are session bookkeeping for the Simulator screen,
     * which is a different thing with different rules.
     */
    public void resetCounters() {
        published.set(0);
        fraudPublished.set(0);
    }
}
