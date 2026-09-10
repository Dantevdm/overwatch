package com.overwatch.simulator.service;

import com.overwatch.common.Topics;
import com.overwatch.common.domain.Transaction;
import com.overwatch.simulator.data.Cardholder;
import com.overwatch.simulator.generate.FraudPattern;
import com.overwatch.simulator.generate.TransactionGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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
    private final SimulatorProperties properties;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger ratePerSecond = new AtomicInteger();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong fraudPublished = new AtomicLong();
    private final AtomicLong historyPublished = new AtomicLong();
    private final AtomicBoolean seeding = new AtomicBoolean();
    private volatile double fraudRate;

    public SimulatorService(KafkaTemplate<String, Object> kafka,
                            SimulatorProperties properties,
                            MeterRegistry meters) {
        this.kafka = kafka;
        this.meters = meters;
        this.properties = properties;
        // Seeded, so the population is the same every run: the same people, the
        // same cards, the same archetypes. A demo script can then name a customer
        // and still find them tomorrow, and the historical backfill below can be
        // republished without inventing a second, different past.
        this.generator = new TransactionGenerator(properties.cardPoolSize(),
                new Random(properties.historySeed()));
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

    // ---- historical backfill -------------------------------------------------

    /**
     * Publish a month of past spending, once, shortly after startup.
     *
     * <p>Live traffic cannot give a cardholder depth. At five transactions a
     * second a person accumulates a usable history in hours, and the obvious
     * shortcut — concentrating live traffic onto fewer cards — trips the velocity
     * rule on ordinary behaviour, which is a failure this system has already had
     * once. Real depth is months of spending, so it is backfilled with past
     * timestamps rather than manufactured now.
     *
     * <p>Two things fall out of it. A customer profile has something to show the
     * moment the stack is up rather than after an hour of watching. And the
     * amount-deviation rule, which needs ten prior transactions on a card before
     * it will commit to a baseline, has one immediately instead of sitting silent
     * on a dashboard panel that then cannot be told apart from a broken query.
     *
     * <p>Backdated transactions cannot trip velocity, which counts inside a
     * ten-minute window — so this is not a burst as far as any rule is concerned,
     * whatever it looks like on a throughput chart.
     *
     * <p>On its own thread, because {@link ApplicationReadyEvent} is dispatched
     * synchronously: seeding inline would hold the readiness probe down for the
     * whole backfill and compose would call the container unhealthy and restart
     * it, which would start the backfill again.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedHistoryOnStartup() {
        if (!properties.seedHistory()) {
            log.info("Historical backfill disabled (overwatch.simulator.seed-history=false)");
            return;
        }
        Thread.ofVirtual().name("history-seed").start(this::seedHistory);
    }

    /**
     * Generate and publish the backfill.
     *
     * <p>Safe to run more than once, which is what makes it safe at all: this
     * service owns no database and cannot ask whether it has already seeded. The
     * generator is seeded from a fixed value, so the same run produces the same
     * transaction ids — a restart republishes byte-identical messages and the
     * engine recognises every one as a redelivery on its primary key. Repeating
     * the backfill costs a redelivery count, not a duplicated past.
     */
    public long seedHistory() {
        if (!seeding.compareAndSet(false, true)) {
            log.info("Historical backfill already running; ignoring the request");
            return 0;
        }
        try {
            List<Cardholder> holders = generator.cardholders();
            Random random = new Random(properties.historySeed());
            Instant now = Instant.now();
            long produced = 0;

            for (Cardholder holder : holders) {
                for (int card = 0; card < holder.cards().size(); card++) {
                    // Poisson per card per day, so people have busy days and
                    // quiet ones rather than exactly 1.4 transactions daily.
                    for (int day = properties.historyDays(); day >= 1; day--) {
                        int count = samplePoisson(properties.historyPerCardPerDay());
                        for (int i = 0; i < count; i++) {
                            Transaction txn =
                                    generator.historical(holder, pastInstant(now, day, random));
                            // Keyed on the transaction's own card, not the
                            // holder's first one. The generator chooses which of
                            // their cards was used, and a key that disagrees with
                            // the payload puts one card's history across two
                            // partitions — where it is no longer ordered, which is
                            // precisely what the velocity rule depends on.
                            kafka.send(Topics.TRANSACTIONS, txn.cardId(), txn);
                            produced++;
                        }
                    }
                }
                // Flushed per holder rather than at the end. The producer buffers
                // in memory, and eighty thousand records is comfortably more than
                // the default 32MB — left to accumulate, the send blocks on a full
                // buffer and the backfill stalls somewhere in the middle with no
                // obvious cause.
                kafka.flush();
            }

            historyPublished.set(produced);
            log.info("Historical backfill complete — {} transactions across {} cardholders, "
                            + "{} days back", produced, holders.size(), properties.historyDays());
            return produced;
        } catch (RuntimeException e) {
            // A failed backfill must not take the service down: live generation is
            // the primary job and works without any history at all.
            log.error("Historical backfill failed part-way through", e);
            return historyPublished.get();
        } finally {
            seeding.set(false);
        }
    }

    /**
     * A moment {@code daysAgo} days back, at an hour of the day people actually
     * spend.
     *
     * <p>Sampled against the same diurnal curve live traffic follows, by
     * rejection. Drawing the hour uniformly would give every backfilled card a
     * flat 24-hour profile, which would make the late-night rule fire on about an
     * eighth of all history and make "spends at odd hours" meaningless as a
     * signal.
     */
    private static Instant pastInstant(Instant now, int daysAgo, Random random) {
        double peak = Arrays.stream(HOURLY_SHAPE).max().orElse(1);
        int hour;
        do {
            hour = random.nextInt(24);
        } while (random.nextDouble() > HOURLY_SHAPE[hour] / peak);

        return now.minus(daysAgo, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
                .plus(hour, ChronoUnit.HOURS)
                .plus(random.nextInt(60), ChronoUnit.MINUTES)
                .plus(random.nextInt(60), ChronoUnit.SECONDS);
    }

    /** How many transactions the backfill published, for the control screen. */
    public long getHistoryPublished() {
        return historyPublished.get();
    }

    public boolean isSeeding() {
        return seeding.get();
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
