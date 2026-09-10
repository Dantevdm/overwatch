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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
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
    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

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
        // The time-of-day multiplier, exposed so the shape of the day is a
        // metric rather than something you have to infer from throughput.
        meters.gauge("simulator.diurnal.weight", this,
                s -> diurnalWeight(ZonedDateTime.now(SAST)));
        log.info("Simulator initialised — {} txn/s, {}% fraud injection, {} cards, running={}",
                ratePerSecond.get(), Math.round(fraudRate * 100),
                properties.cardPoolSize(), running.get());
    }

    /**
     * Relative traffic volume by hour of the day, SAST, 00:00 first.
     *
     * <p>Card spend has a shape: nothing overnight, a commute-and-coffee rise from
     * six, a lunch peak, a second and larger peak as people shop on the way home,
     * then a decline through the evening. Without it the throughput chart is a
     * perfectly straight line — measured at 28.0 tx/s with a standard deviation of
     * 0.01 over forty minutes, which is not a plausible reading of anything.
     *
     * <p>Interpolated between hours rather than stepped, because twenty-four flat
     * steps is just a coarser straight line.
     */
    private static final double[] HOURLY_SHAPE = {
            0.12, 0.07, 0.05, 0.05, 0.07, 0.15,   // 00:00–05:00, the trough
            0.40, 0.85, 1.05, 1.10, 1.15, 1.30,   // 06:00–11:00, morning build
            1.65, 1.55, 1.25, 1.20, 1.35, 1.75,   // 12:00–17:00, lunch and commute
            1.80, 1.55, 1.15, 0.80, 0.45, 0.22};  // 18:00–23:00, evening decline

    /** Mean of the curve, so the configured rate is a daily average, not a peak. */
    private static final double SHAPE_MEAN = Arrays.stream(HOURLY_SHAPE).average().orElse(1);

    /**
     * One second's worth of traffic.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}, and the difference is not
     * academic. {@code fixedRate} schedules the next run at <em>previous start +
     * period</em>, so when the scheduler thread is starved — a busy host, a long
     * GC, a suspended VM — every missed tick is then fired back to back to catch
     * up. That is exactly what happened here: roughly three minutes of starvation
     * replayed as some sixteen thousand ticks, 116 000 transactions in three
     * minutes against a configured five per second. Every card then had fifty-odd
     * transactions inside the ten-minute velocity window, so the velocity rule
     * fired on 99.5% of traffic and the alert store filled with 111 692
     * indistinguishable MEDIUM alerts. The rule was right; its input was not.
     *
     * <p>{@code fixedDelay} measures from the previous <em>completion</em>, so a
     * stall costs the traffic that would have happened during it and nothing more.
     * That is the correct trade for a generator: under-produce while the host is
     * struggling rather than manufacture a spike that never occurred and cannot be
     * distinguished from a real one afterwards.
     */
    @Scheduled(fixedDelay = TICK_MS)
    public void tick() {
        if (!running.get()) {
            return;
        }
        double expected = ratePerSecond.get() * diurnalWeight(ZonedDateTime.now(SAST));
        int budget = samplePoisson(expected);
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

    /**
     * How busy this moment of the day is, relative to the daily average.
     *
     * <p>Normalised by the mean of the curve, so the configured rate stays the
     * <em>average</em> over a day rather than becoming a peak. Setting 20/s means
     * roughly 20/s averaged across 24 hours: about 36/s at the evening peak and
     * about 1/s at four in the morning.
     */
    static double diurnalWeight(ZonedDateTime when) {
        int hour = when.getHour();
        // Fraction of the way to the next hour, so the curve slides rather than
        // stepping at the top of each hour.
        double progress = (when.getMinute() * 60 + when.getSecond()) / 3600.0;
        double from = HOURLY_SHAPE[hour];
        double to = HOURLY_SHAPE[(hour + 1) % HOURLY_SHAPE.length];
        return (from + (to - from) * progress) / SHAPE_MEAN;
    }

    /**
     * A Poisson draw around the expected count for this second.
     *
     * <p>Emitting exactly N transactions every tick is what made the throughput
     * chart a ruler. Arrivals that are independent of one another are Poisson
     * distributed — which is not a decoration but the actual model for
     * transactions hitting an acquirer — so the count jitters around the mean the
     * way a real feed does, and bursts and lulls appear without being scripted.
     *
     * <p>Knuth's method: multiply uniforms until the product falls below e^-λ.
     * Fine at these rates; it degrades above λ≈700 where e^-λ underflows, which
     * would need a rate three orders of magnitude beyond anything this demo runs.
     */
    private static int samplePoisson(double lambda) {
        if (lambda <= 0) {
            return 0;
        }
        double limit = Math.exp(-lambda);
        double product = 1.0;
        int count = 0;
        do {
            count++;
            product *= ThreadLocalRandom.current().nextDouble();
        } while (product > limit);
        return count - 1;
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
