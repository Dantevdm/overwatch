package com.overwatch.api.service;

import com.overwatch.api.dto.Dtos.*;
import com.overwatch.api.repository.TransactionReadRepository;
import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Transaction;
import com.overwatch.common.persistence.TransactionEntity;
import com.overwatch.engine.rule.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Answers "what would this rule have caught?" without changing anything.
 *
 * <p>Replays stored transactions through a candidate configuration and reports the
 * alerts it <em>would</em> have produced. Nothing is written and no rule is
 * altered, which is the entire point: "should we drop the high-value threshold to
 * R30 000?" becomes a measurement rather than an argument.
 *
 * <p>This is also the natural companion to shadow mode. Shadow evaluates a rule
 * forward against live traffic; replay evaluates it backward against history. Use
 * replay to pick a starting threshold, shadow to confirm it, then promote.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    private static final int MAX_HOURS = 24 * 7;
    private static final int SAMPLE_LIMIT = 20;

    /**
     * Most transactions a single replay will read.
     *
     * <p>Seven days at five per second is about three million rows, and the
     * window cap alone therefore bounds nothing that matters. Replay reads the
     * most recent {@code REPLAY_LIMIT} inside the window and says so, which is
     * both honest and the right sample: a threshold is being chosen for traffic
     * that looks like now, not like last Tuesday.
     */
    private static final int REPLAY_LIMIT = 200_000;

    /** Candidate values a sweep should offer, so the UI does not invent them. */
    private static final List<Sweepable> SWEEPABLE = List.of(
            new Sweepable("HIGH_VALUE", "threshold",
                    List.of(10_000, 20_000, 30_000, 50_000, 75_000, 100_000, 150_000),
                    "ZAR",
                    "Where the line between a large purchase and a suspicious one sits."),
            new Sweepable("ROUND_AMOUNT", "floor",
                    List.of(1_000, 2_500, 5_000, 10_000, 20_000, 50_000),
                    "ZAR",
                    "Below some amount, a round number is just a round number."),
            new Sweepable("LATE_NIGHT", "endHour",
                    List.of(2, 3, 4, 5, 6, 7),
                    "hour, SAST",
                    "How far into the morning the small hours are taken to run."),
            new Sweepable("CROSS_BORDER", null, List.of(), null,
                    "Configured by country list rather than by a number, so there "
                    + "is no threshold to sweep. Replay it directly instead."),
            new Sweepable("CATEGORY_WATCHLIST", null, List.of(), null,
                    "Configured by category list rather than by a number, so there "
                    + "is no threshold to sweep. Replay it directly instead."),
            new Sweepable("VELOCITY", null, List.of(), null,
                    "Counts transactions on a card inside a window, and replay has "
                    + "no card history to count -- see the note on replaying "
                    + "history-dependent rules. Use shadow mode for this one."),
            new Sweepable("AMOUNT_DEVIATION", null, List.of(), null,
                    "Compares against a per-card baseline that replay cannot "
                    + "reconstruct. Use shadow mode for this one."));

    private final TransactionReadRepository transactions;
    private final Map<String, FraudRule> rulesByType;

    public ReplayService(TransactionReadRepository transactions, List<FraudRule> rules) {
        this.transactions = transactions;
        this.rulesByType = new HashMap<>();
        rules.forEach(r -> rulesByType.put(r.ruleType(), r));
    }

    public Set<String> availableRuleTypes() {
        return Collections.unmodifiableSet(rulesByType.keySet());
    }

    /** What each rule can be swept on. Includes the rules that cannot, and why. */
    public List<Sweepable> sweepable() {
        return SWEEPABLE.stream()
                .filter(s -> rulesByType.containsKey(s.ruleType()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReplayResult replay(ReplayRequest request) {
        FraudRule rule = rulesByType.get(request.ruleType());
        if (rule == null) {
            throw new IllegalArgumentException(
                    "Unknown rule type '" + request.ruleType() + "'. Available: " + rulesByType.keySet());
        }

        int hours = Math.max(1, Math.min(request.hours() <= 0 ? 24 : request.hours(), MAX_HOURS));
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        RuleParameters params = RuleParameters.of(
                request.parameters() == null ? Map.of() : request.parameters());

        TransactionHistory noHistory = inertHistory();

        long evaluated = 0;
        long fired = 0;
        List<ReplaySample> samples = new ArrayList<>();

        for (TransactionEntity entity : window(since)) {
            evaluated++;
            Optional<RuleFinding> finding =
                    rule.evaluate(new RuleContext(toDomain(entity), params, noHistory));
            if (finding.isPresent()) {
                fired++;
                if (samples.size() < SAMPLE_LIMIT) {
                    samples.add(new ReplaySample(entity.getId(), entity.getMerchantName(),
                            entity.getAmount(), finding.get().reason()));
                }
            }
        }

        double pct = evaluated == 0 ? 0 : (double) fired / evaluated * 100;
        log.info("Replay of {} over {}h: {} of {} transactions would have fired ({}%)",
                request.ruleType(), hours, fired, evaluated, String.format("%.2f", pct));

        return new ReplayResult(request.ruleType(), request.parameters(), hours,
                evaluated, fired, pct, samples);
    }

    /**
     * Sweep one parameter across candidate values in a single pass.
     *
     * <p>The point of doing it here rather than by calling {@link #replay} once
     * per value is the single pass. Each transaction is read once and handed to
     * the rule once per candidate, so sweeping seven thresholds costs one trip
     * through the history and seven cheap in-memory evaluations per row --
     * against seven full reads if the caller loops. The rules are documented as
     * stateless and side-effect free, which is exactly what makes reusing the row
     * across candidates sound.
     *
     * <p>Candidates are evaluated independently. A monotone rule will produce a
     * monotone curve, but nothing here assumes that, because not every parameter
     * is a threshold -- LATE_NIGHT's endHour widens a window rather than moving a
     * line, and a rule added later need not be monotone at all.
     */
    @Transactional(readOnly = true)
    public SweepResult sweep(SweepRequest request) {
        FraudRule rule = rulesByType.get(request.ruleType());
        if (rule == null) {
            throw new IllegalArgumentException(
                    "Unknown rule type '" + request.ruleType() + "'. Available: "
                    + rulesByType.keySet());
        }
        if (request.parameter() == null || request.parameter().isBlank()) {
            throw new IllegalArgumentException("A parameter to sweep is required.");
        }
        List<Object> values = request.values();
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("At least one candidate value is required.");
        }
        // Bounded. Each candidate is another evaluation of every row, so the cost
        // is rows x candidates and a caller should not be able to ask for a
        // million of them by editing a JSON body.
        if (values.size() > 25) {
            throw new IllegalArgumentException(
                    "At most 25 candidate values; asked for " + values.size() + ".");
        }

        int hours = Math.max(1, Math.min(request.hours() <= 0 ? 24 : request.hours(), MAX_HOURS));
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        Map<String, Object> base = new LinkedHashMap<>(
                request.baseParameters() == null ? Map.of() : request.baseParameters());

        // Parameters built once per candidate, outside the row loop. Building
        // them per row would dominate the measurement and tell you about
        // RuleParameters rather than about the rule.
        List<RuleParameters> candidates = values.stream().map(value -> {
            Map<String, Object> merged = new LinkedHashMap<>(base);
            merged.put(request.parameter(), value);
            return RuleParameters.of(merged);
        }).toList();

        TransactionHistory noHistory = inertHistory();
        long[] fired = new long[candidates.size()];
        long evaluated = 0;

        for (TransactionEntity entity : window(since)) {
            Transaction txn = toDomain(entity);
            evaluated++;
            for (int i = 0; i < candidates.size(); i++) {
                if (rule.evaluate(new RuleContext(txn, candidates.get(i), noHistory)).isPresent()) {
                    fired[i]++;
                }
            }
        }

        long total = evaluated;
        List<SweepPoint> points = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            points.add(new SweepPoint(values.get(i), fired[i],
                    total == 0 ? 0 : (double) fired[i] / total * 100));
        }

        log.info("Sweep of {}.{} over {}h across {} candidates: {} transactions evaluated",
                request.ruleType(), request.parameter(), hours, values.size(), evaluated);

        return new SweepResult(request.ruleType(), request.parameter(), hours,
                evaluated, evaluated >= REPLAY_LIMIT, points);
    }

    /**
     * The transactions a replay or sweep will read: newest first, inside the
     * window, capped.
     */
    private List<TransactionEntity> window(Instant since) {
        return transactions.findByOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                since, PageRequest.of(0, REPLAY_LIMIT));
    }

    /**
     * History lookups that answer nothing.
     *
     * <p>Replay is read-only and evaluates rows out of their original order, so
     * there is no coherent card history to offer. A rule that needs one therefore
     * reports nothing rather than producing a confident answer from a baseline
     * that does not correspond to the window being replayed -- which is why
     * VELOCITY and AMOUNT_DEVIATION are listed as not sweepable, with the reason
     * attached, instead of quietly returning zero.
     */
    private static TransactionHistory inertHistory() {
        return new TransactionHistory() {
            @Override public long countRecent(String cardId, Duration window) { return 0; }
            @Override public Optional<BigDecimal> averageAmount(String c, int m) {
                return Optional.empty();
            }
        };
    }

    private static Transaction toDomain(TransactionEntity e) {
        Channel channel;
        try {
            channel = Channel.valueOf(e.getChannel());
        } catch (IllegalArgumentException ex) {
            channel = Channel.ONLINE;
        }
        return new Transaction(e.getId(), e.getCardId(),
                e.getCustomerId(), e.getCustomerName(),
                e.getAmount(), e.getCurrency(),
                e.getMerchantName(), e.getMerchantCategory(), e.getCountryCode(),
                channel, e.getOccurredAt(), e.getMetadata());
    }
}
