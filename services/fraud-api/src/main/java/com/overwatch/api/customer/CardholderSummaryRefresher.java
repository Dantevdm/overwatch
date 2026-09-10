package com.overwatch.api.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps {@code cardholder_summary} current.
 *
 * <p>The view exists so the cardholder directory costs one aggregate per refresh
 * rather than one per reader — see V7. This is the refresh.
 *
 * <p>{@code CONCURRENTLY} is not optional. Without it a refresh takes an ACCESS
 * EXCLUSIVE lock on the view and every reader of the directory blocks for its
 * duration; with it, readers keep seeing the previous contents until the new
 * ones are ready. The cost is that it cannot run inside a transaction, which is
 * why this uses a plain {@link JdbcTemplate} and carries no
 * {@code @Transactional} — one would turn every refresh into an error about
 * running CONCURRENTLY in a transaction block.
 */
@Component
public class CardholderSummaryRefresher {

    private static final Logger log = LoggerFactory.getLogger(CardholderSummaryRefresher.class);

    /** No caller input goes anywhere near this. */
    private static final String REFRESH =
            "REFRESH MATERIALIZED VIEW CONCURRENTLY cardholder_summary";

    private final JdbcTemplate jdbc;
    private final long intervalMs;

    /**
     * True while a refresh is running.
     *
     * <p>A refresh over a growing table eventually takes longer than the interval
     * between refreshes, and {@code fixedDelay} alone does not protect against
     * that if a refresh is also triggered by a reset. Overlapping refreshes would
     * queue on each other's locks rather than fail, which is worse: they would
     * silently consume a connection each.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CardholderSummaryRefresher(
            JdbcTemplate jdbc,
            @Value("${overwatch.api.cardholder-summary-refresh-ms:30000}") long intervalMs) {
        this.jdbc = jdbc;
        this.intervalMs = intervalMs;
        log.info("Cardholder summary refreshes every {}ms", intervalMs);
    }

    /**
     * {@code fixedDelay}, never {@code fixedRate}.
     *
     * <p>fixedRate schedules from the previous execution's start, so a refresh
     * that overruns the interval is followed immediately by the next one, and a
     * scheduler that stalls replays every missed execution back to back. That
     * exact mistake in the simulator once published a hundred thousand
     * transactions in a burst and poisoned the whole data set; it is not a
     * mistake worth making twice.
     */
    @Scheduled(fixedDelayString = "${overwatch.api.cardholder-summary-refresh-ms:30000}",
               initialDelay = 5_000)
    public void scheduledRefresh() {
        refresh();
    }

    /**
     * Refresh now, if one is not already in flight.
     *
     * <p>Called on a schedule and by the reset endpoint: clearing the store has
     * to empty the directory while the person who pressed the button is looking
     * at it, not thirty seconds later.
     *
     * @return true if this call performed a refresh
     */
    public boolean refresh() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Cardholder summary refresh already in flight; skipping this one");
            return false;
        }
        long started = System.nanoTime();
        try {
            jdbc.execute(REFRESH);
            log.debug("Cardholder summary refreshed in {}ms",
                    (System.nanoTime() - started) / 1_000_000);
            return true;
        } catch (RuntimeException e) {
            // Logged and swallowed. A stale directory is a degraded screen; a
            // refresher that dies takes the schedule with it and degrades it
            // permanently, which is strictly worse.
            log.warn("Could not refresh the cardholder summary: {}", e.getMessage());
            return false;
        } finally {
            running.set(false);
        }
    }

    public long intervalMs() {
        return intervalMs;
    }
}
