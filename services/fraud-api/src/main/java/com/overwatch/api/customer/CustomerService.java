package com.overwatch.api.customer;

import com.overwatch.api.customer.CustomerView.AlertLine;
import com.overwatch.api.customer.CustomerView.Profile;
import com.overwatch.api.customer.CustomerView.RiskProfile;
import com.overwatch.api.customer.CustomerView.RiskSignal;
import com.overwatch.api.customer.CustomerView.Slice;
import com.overwatch.api.customer.CustomerView.Summary;
import com.overwatch.api.customer.CustomerView.TransactionLine;
import com.overwatch.api.repository.AlertRepository;
import com.overwatch.api.repository.TransactionReadRepository;
import com.overwatch.common.persistence.AlertRuleHitEntity;
import com.overwatch.common.persistence.FraudAlertEntity;
import com.overwatch.common.persistence.TransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The cardholder 360 view: one person, everything observed about them.
 *
 * <p>Every rule in this system evaluates a single transaction, which is the right
 * unit for a real-time decision and the wrong unit for an investigation. An
 * analyst handed an alert does not ask "is this transaction odd" — they ask who
 * this is, what their spending normally looks like, and whether this fits. That
 * question has no answer anywhere else in the system, because until V4 the finest
 * identity it held was the card, and a person carrying two cards was two
 * subjects.
 *
 * <p>Built entirely by aggregating observed transactions. There is no customer
 * table and deliberately so: this system watches a payment stream and does not
 * own customer master data, so a cardholder here is exactly the set of
 * transactions that named them. A person with no transactions correctly does not
 * exist, and nothing can drift out of step with whatever really owns the customer
 * record.
 */
@Service
public class CustomerService {

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    /**
     * How much history one profile will read.
     *
     * <p>The aggregates have to be computed over the whole set — a category mix
     * derived from one page is not a category mix — so the read is unpaged in
     * spirit and has to be bounded in practice. Five thousand transactions is far
     * more than any cardholder in this system accumulates, and when the cap does
     * bite the profile says so rather than presenting a sample as a summary.
     */
    private static final int HISTORY_CAP = 5_000;

    /** Alerts shown on a profile. Beyond this the timeline stops being readable. */
    private static final int ALERT_CAP = 50;

    /** Recent transactions shown on the timeline. */
    private static final int RECENT_CAP = 25;

    /** Categories a bank would look at twice, for the risk summary. */
    private static final Set<String> HIGH_RISK_CATEGORIES =
            Set.of("crypto", "gambling", "forex");

    /** The window the velocity signal measures against, matching the rule's default. */
    private static final Duration VELOCITY_WINDOW = Duration.ofMinutes(10);

    private final TransactionReadRepository transactions;
    private final AlertRepository alerts;

    public CustomerService(TransactionReadRepository transactions, AlertRepository alerts) {
        this.transactions = transactions;
        this.alerts = alerts;
    }

    /** Cardholders observed, most active first, optionally filtered by name or id. */
    @Transactional(readOnly = true)
    public Page<Summary> search(String query, Pageable pageable) {
        Page<Object[]> rows = transactions.searchCustomers(likePattern(query), pageable);

        return rows.map(row -> {
            String id = (String) row[0];
            return new Summary(
                    id,
                    (String) row[1],
                    ((Number) row[2]).longValue(),
                    (BigDecimal) row[3],
                    (Instant) row[4],
                    ((Number) row[5]).intValue(),
                    // Counted per row rather than joined into the grouped query.
                    // Adding alerts to that GROUP BY would multiply the
                    // transaction count by the number of alerts each raised,
                    // which is the classic fan-out that makes a dashboard figure
                    // quietly several times too large.
                    alertsFor(id));
        });
    }

    /**
     * A LIKE pattern for a user's search text, or {@code %} for "everything".
     *
     * <p>Lowercased and wrapped here rather than in the query. The database cannot
     * infer a type for a parameter that appears only inside {@code CONCAT}, and
     * resolves it to {@code bytea} — which fails at runtime on
     * {@code function lower(bytea) does not exist}, a message pointing nowhere
     * near the cause. See the repository method for the full account.
     *
     * <p>The user's own {@code %} and {@code _} are escaped. Unescaped, typing a
     * single underscore matches every cardholder whose name has any character in
     * that position, which reads as a search box that ignores what you type.
     */
    private static String likePattern(String query) {
        if (query == null || query.isBlank()) return "%";
        String escaped = query.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * One cardholder's full profile.
     *
     * <p>Reads their history once and derives everything from it in memory rather
     * than issuing a query per panel. Nine aggregate queries against the same
     * bounded row set would be nine index scans to answer one question, and they
     * could disagree with each other — a category mix and a total computed at
     * different instants do not have to add up, and on a live stream they will
     * not.
     */
    @Transactional(readOnly = true)
    public Profile profile(String customerId) {
        List<TransactionEntity> history = transactions
                .findByCustomerIdOrderByOccurredAtDesc(customerId, PageRequest.of(0, HISTORY_CAP));

        if (history.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No cardholder '" + customerId + "' has been observed. This system "
                            + "builds cardholders from the transactions that named them, so "
                            + "one with no transactions does not exist here.");
        }

        long total = transactions.countByCustomerId(customerId);
        TransactionEntity newest = history.getFirst();
        TransactionEntity oldest = history.getLast();

        BigDecimal spend = history.stream()
                .map(TransactionEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal largest = history.stream()
                .map(TransactionEntity::getAmount)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        BigDecimal average = spend.divide(BigDecimal.valueOf(history.size()), 2, RoundingMode.HALF_UP);

        List<FraudAlertEntity> theirAlerts =
                alerts.findByCustomer(customerId, PageRequest.of(0, ALERT_CAP));
        Set<UUID> flagged = new HashSet<>();
        theirAlerts.forEach(a -> flagged.add(a.getTransactionId()));

        return new Profile(
                customerId,
                newest.getCustomerName(),
                // Home city and bank come off the stream's metadata rather than a
                // customer record, so the most recent observation wins. They are
                // stable per holder by construction; taking the newest means a
                // change would show up rather than being averaged into nothing.
                newest.getMetadata().getOrDefault("city", "—"),
                newest.getMetadata().getOrDefault("issuingBank", "—"),
                history.stream().map(TransactionEntity::getCardId).distinct().sorted().toList(),
                total,
                total > history.size(),
                spend, average, largest,
                oldest.getOccurredAt(), newest.getOccurredAt(),
                sliceBy(history, TransactionEntity::getMerchantCategory),
                sliceBy(history, TransactionEntity::getCountryCode),
                sliceBy(history, TransactionEntity::getChannel),
                byHourOfDay(history),
                risk(history, theirAlerts, customerId),
                theirAlerts.stream().map(CustomerService::toAlertLine).toList(),
                history.stream().limit(RECENT_CAP)
                        .map(t -> toTransactionLine(t, flagged.contains(t.getId())))
                        .toList());
    }

    // ---- the risk summary ----------------------------------------------------

    /**
     * A behavioural summary of one cardholder, as evidence rather than a verdict.
     *
     * <p>Every signal is a share of their own observed behaviour, and every one
     * carries the measurement that produced it. That is the whole design: an
     * investigator has to be able to look at "22% of spend acquired abroad" and
     * say "they work in Nairobi", and know precisely which line to discount. A
     * single opaque number cannot be argued with, and a number that cannot be
     * argued with is not evidence.
     *
     * <p>Deliberately not a model. A learned score needs labelled outcomes, and
     * nothing in this system has confirmed a single alert as fraud — the
     * confirmed and cleared counts on the rule-performance endpoint are all zero.
     * Fitting anything to that would be inventing an authority the data cannot
     * support.
     */
    private RiskProfile risk(List<TransactionEntity> history,
                             List<FraudAlertEntity> theirAlerts,
                             String customerId) {
        int n = history.size();
        List<RiskSignal> signals = new ArrayList<>();

        long foreign = history.stream().filter(t -> !"ZA".equals(t.getCountryCode())).count();
        signals.add(signal("Spend acquired abroad", foreign, n, 20, 0.05,
                "%d of %d transactions outside South Africa".formatted(foreign, n)));

        long night = history.stream().filter(CustomerService::inTheSmallHours).count();
        signals.add(signal("Spend in the small hours", night, n, 15, 0.08,
                "%d of %d transactions between 01:00 and 05:00 SAST".formatted(night, n)));

        long highRisk = history.stream()
                .filter(t -> HIGH_RISK_CATEGORIES.contains(t.getMerchantCategory())).count();
        signals.add(signal("High-risk merchant categories", highRisk, n, 20, 0.02,
                "%d of %d at crypto, gambling or forex merchants".formatted(highRisk, n)));

        long cnp = history.stream()
                .filter(t -> "ONLINE".equals(t.getChannel()) || "MOBILE".equals(t.getChannel()))
                .count();
        signals.add(signal("Card-not-present", cnp, n, 10, 0.40,
                "%d of %d online or in-app".formatted(cnp, n)));

        int velocityPeak = busiestWindow(history);
        boolean velocityElevated = velocityPeak > 5;
        signals.add(new RiskSignal("Busiest 10-minute window",
                "%d transactions on one card".formatted(velocityPeak),
                velocityElevated ? 15 : 0, velocityElevated));

        long critical = theirAlerts.stream()
                .filter(a -> "CRITICAL".equals(a.getSeverity()) || "HIGH".equals(a.getSeverity()))
                .count();
        boolean severeAlerts = critical > 0;
        signals.add(new RiskSignal("High or critical alerts",
                critical == 0 ? "none raised" : "%d raised".formatted(critical),
                (int) Math.min(20, critical * 7), severeAlerts));

        int score = Math.min(100, signals.stream().mapToInt(RiskSignal::points).sum());
        String band = score >= 50 ? "ELEVATED" : score >= 25 ? "WATCH" : "NORMAL";

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Object[] row : alerts.countBySeverityForCustomer(customerId)) {
            bySeverity.put((String) row[0], ((Number) row[1]).longValue());
        }

        return new RiskProfile(band, score, signals,
                theirAlerts.size(), bySeverity,
                "A heuristic over observed behaviour, not a model. Each line is a share "
                        + "of this cardholder's own history and can be discounted on its "
                        + "own — a frequent traveller's foreign spend is not fraud. Nothing "
                        + "here has been trained on confirmed outcomes, because none of the "
                        + "alerts in this system have been confirmed.");
    }

    /**
     * One proportional signal.
     *
     * <p>Points scale with how far past the population norm the share sits, capped
     * at the signal's maximum. Below the norm contributes nothing — a cardholder
     * who does less of something than everyone else is not evidence of anything,
     * and letting every signal contribute a little would give every person in the
     * book the same middling score.
     */
    private static RiskSignal signal(String label, long count, int total,
                                     int maxPoints, double norm, String detail) {
        double share = total == 0 ? 0 : (double) count / total;
        boolean elevated = share > norm;
        int points = 0;
        if (elevated && norm > 0) {
            // Saturates at four times the norm, so one outlier behaviour cannot
            // reach ELEVATED on its own.
            double excess = Math.min(1.0, (share - norm) / (norm * 3));
            points = (int) Math.round(maxPoints * excess);
        }
        return new RiskSignal(label, "%s (%.1f%%)".formatted(detail, share * 100), points, elevated);
    }

    /**
     * The most transactions this cardholder had on one card inside ten minutes.
     *
     * <p>Per card, not per person, because that is what the velocity rule counts —
     * a figure computed across someone's three cards would not be comparable to
     * the threshold it is shown against.
     *
     * <p>A sliding window over each card's timeline. The history arrives newest
     * first, so it is walked in reverse.
     */
    private static int busiestWindow(List<TransactionEntity> history) {
        Map<String, List<Instant>> byCard = new LinkedHashMap<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            TransactionEntity t = history.get(i);
            byCard.computeIfAbsent(t.getCardId(), k -> new ArrayList<>()).add(t.getOccurredAt());
        }

        int peak = 0;
        for (List<Instant> times : byCard.values()) {
            int start = 0;
            for (int end = 0; end < times.size(); end++) {
                while (Duration.between(times.get(start), times.get(end))
                        .compareTo(VELOCITY_WINDOW) > 0) {
                    start++;
                }
                peak = Math.max(peak, end - start + 1);
            }
        }
        return peak;
    }

    // ---- breakdowns ----------------------------------------------------------

    /**
     * A breakdown by some property of a transaction, largest first.
     *
     * <p>Carries both a count and a value, because they answer different
     * questions: someone can make four per cent of their transactions at a crypto
     * merchant and spend sixty per cent of their money there.
     */
    private static List<Slice> sliceBy(List<TransactionEntity> history,
                                       java.util.function.Function<TransactionEntity, String> key) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (TransactionEntity t : history) {
            String label = key.apply(t);
            if (label == null) label = "—";
            counts.computeIfAbsent(label, k -> new long[1])[0]++;
            values.merge(label, t.getAmount(), BigDecimal::add);
        }

        int total = history.size();
        return counts.entrySet().stream()
                .map(e -> new Slice(e.getKey(), e.getValue()[0],
                        values.get(e.getKey()),
                        total == 0 ? 0 : (double) e.getValue()[0] / total))
                .sorted(Comparator.comparingLong(Slice::count).reversed())
                .toList();
    }

    /**
     * Transactions by hour of day, SAST, as 24 buckets.
     *
     * <p>Local time, not UTC. The whole point of the shape is whether this person
     * spends at hours people do not, and South African card spend read in UTC is
     * shifted two hours — which would put the evening peak at 16:00 and make the
     * late-night band look busy.
     */
    private static List<Long> byHourOfDay(List<TransactionEntity> history) {
        Map<Integer, Long> counts = new TreeMap<>();
        for (int hour = 0; hour < 24; hour++) counts.put(hour, 0L);
        for (TransactionEntity t : history) {
            int hour = t.getOccurredAt().atZone(SAST).getHour();
            counts.merge(hour, 1L, Long::sum);
        }
        return List.copyOf(counts.values());
    }

    private static boolean inTheSmallHours(TransactionEntity t) {
        int hour = t.getOccurredAt().atZone(SAST).getHour();
        return hour >= 1 && hour < 5;
    }

    private long alertsFor(String customerId) {
        return alerts.countBySeverityForCustomer(customerId).stream()
                .mapToLong(row -> ((Number) row[1]).longValue())
                .sum();
    }

    // ---- mapping -------------------------------------------------------------

    private static AlertLine toAlertLine(FraudAlertEntity a) {
        return new AlertLine(a.getId(), a.getTransactionId(),
                a.getRiskScore().doubleValue(), a.getSeverity(), a.getStatus(),
                a.getAmount(), a.getOccurredAt(),
                a.getHits().stream().map(AlertRuleHitEntity::getRuleType).distinct().sorted().toList());
    }

    private static TransactionLine toTransactionLine(TransactionEntity t, boolean flagged) {
        return new TransactionLine(t.getId(), t.getCardId(), t.getAmount(),
                t.getMerchantName(), t.getMerchantCategory(), t.getCountryCode(),
                t.getChannel(), t.getOccurredAt(), flagged);
    }
}
