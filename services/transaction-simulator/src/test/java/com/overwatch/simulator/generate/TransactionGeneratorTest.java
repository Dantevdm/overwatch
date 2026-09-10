package com.overwatch.simulator.generate;

import com.overwatch.common.domain.Transaction;
import com.overwatch.simulator.data.SouthAfricanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransactionGenerator")
class TransactionGeneratorTest {

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    /**
     * Midday SAST, fixed. Ordinary traffic is now stamped with the real clock
     * rather than scattered across a random hour, so without a fixed clock every
     * assertion about timestamps here would depend on what time the suite ran —
     * and the late-night assertions would fail for anyone running the build
     * between 01:00 and 05:00.
     */
    private static final Clock NOON =
            Clock.fixed(ZonedDateTime.of(2026, 3, 12, 12, 30, 0, 0, SAST).toInstant(), SAST);

    /** Seeded, so a failure here is reproducible rather than a flake. */
    private final TransactionGenerator generator =
            new TransactionGenerator(2000, new Random(42), NOON);

    @Test
    @DisplayName("normal traffic is overwhelmingly local, in ZAR, and plausibly priced")
    void normalTrafficLooksReal() {
        List<Transaction> sample = java.util.stream.Stream
                .generate(generator::normal).limit(2000).toList();

        assertTrue(sample.stream().allMatch(t -> "ZAR".equals(t.currency())));
        assertTrue(sample.stream().allMatch(t -> t.amount().compareTo(BigDecimal.ZERO) > 0));

        // Not "all local" any more, and that is the point. A minority of
        // cardholders genuinely spend abroad, which is what gives the cross-border
        // rule an honest false positive to argue about — a population where every
        // foreign transaction is fraud makes the rule correct by construction and
        // the demo unable to pose the question a fraud system exists to answer.
        // Still a minority, though: if most traffic were foreign the rule would
        // have nothing to distinguish.
        long local = sample.stream()
                .filter(t -> SouthAfricanData.HOME_COUNTRY.equals(t.countryCode())).count();
        double localShare = (double) local / sample.size();
        assertTrue(localShare > 0.90 && localShare < 1.0,
                "expected a large local majority with a real foreign minority, got "
                        + Math.round(localShare * 100) + "% local");

        // Skewed toward small amounts, as real card spend is. A mean in the tens of
        // thousands would mean the "high value" rule has nothing to distinguish.
        double mean = sample.stream().mapToDouble(t -> t.amount().doubleValue()).average().orElseThrow();
        assertTrue(mean > 100 && mean < 3000, "implausible mean spend: R" + mean);
    }

    @Test
    @DisplayName("a card always belongs to the same cardholder, with one bank and one home city")
    void cardholdersAreCoherent() {
        Map<String, String> holderByCard = new HashMap<>();
        Map<String, String> nameById = new HashMap<>();
        Map<String, String> bankById = new HashMap<>();

        for (Transaction t : java.util.stream.Stream
                .generate(generator::normal).limit(3000).toList()) {
            assertNotNull(t.customerId(), "every generated transaction must name a cardholder");
            assertNotNull(t.customerName());

            // The property the whole 360 view rests on. Before the customer book
            // existed, the bank and city were redrawn per transaction, so one
            // person appeared to bank with four institutions and live in nine
            // cities — which is not a detail nobody notices, it is exactly what
            // makes a profile screen worthless.
            assertEquals(holderByCard.computeIfAbsent(t.cardId(), c -> t.customerId()),
                    t.customerId(), "card " + t.cardId() + " changed hands");
            assertEquals(nameById.computeIfAbsent(t.customerId(), c -> t.customerName()),
                    t.customerName(), t.customerId() + " changed name");
            assertEquals(bankById.computeIfAbsent(t.customerId(),
                            c -> t.metadata().get("issuingBank")),
                    t.metadata().get("issuingBank"), t.customerId() + " changed bank");
        }
    }

    @Test
    @DisplayName("the population is heterogeneous — not everyone spends the same way")
    void cardholdersDifferFromEachOther() {
        List<Transaction> sample = java.util.stream.Stream
                .generate(generator::normal).limit(3000).toList();

        // A generator that emits only the population mean makes every alert
        // correct by construction: there is no such thing as behaviour that is
        // unusual for the population but ordinary for this person, which is most
        // of what a fraud analyst actually reasons about.
        Map<String, Double> meanByHolder = sample.stream().collect(
                Collectors.groupingBy(Transaction::customerId,
                        Collectors.averagingDouble(t -> t.amount().doubleValue())));

        double spread = meanByHolder.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                / Math.max(1, meanByHolder.values().stream()
                        .mapToDouble(Double::doubleValue).min().orElseThrow());
        assertTrue(spread > 5,
                "cardholders should have visibly different spending baselines; "
                        + "widest was only " + Math.round(spread) + "x the narrowest");
    }

    @Test
    @DisplayName("normal traffic is stamped now, never in the future")
    void normalTrafficIsStampedNow() {
        // The previous generator drew a random hour between 07:00 and 22:00
        // without consulting the clock, so a transaction created at 21:21 could
        // be stamped 21:59 — in a live sample, 47 rows out of 1316 were dated in
        // the future. It also made every time-series chart a flat block fifteen
        // hours wide, because the arrival time carried no information at all.
        Instant now = NOON.instant();

        assertTrue(java.util.stream.Stream.generate(generator::normal).limit(1000)
                        .allMatch(t -> !t.timestamp().isAfter(now)),
                "a transaction must never be dated in the future");

        // Almost all within the last second: recent enough that a five-minute
        // window on the dashboard contains the traffic generated during it.
        //
        // Almost, not all. A late-hours cardholder's ordinary spend is stamped in
        // the most recent small-hours window, which is deliberately in the past —
        // that is a real person shopping at 02:00, not a clock fault. The
        // invariant that matters is the one above: never in the future.
        //
        // The band is derived, not guessed. Weighting each archetype's night rate
        // by its share of the population gives about 5.6% backdated, so roughly
        // 944 of 1000 land in the last second. Bounded on both sides: too few and
        // the clock is wrong, too many and the late-hours cardholders have
        // silently stopped existing, which would quietly remove the honest
        // late-night false positive the demo depends on.
        long recent = java.util.stream.Stream.generate(generator::normal).limit(1000)
                .filter(t -> Duration.between(t.timestamp(), now).toSeconds() <= 1)
                .count();
        assertTrue(recent > 900 && recent < 990,
                "expected about 944 of 1000 stamped now, with a small late-hours "
                        + "minority backdated; got " + recent);
    }

    @Test
    @DisplayName("night-time traffic is late-night, and that is the rule's job")
    void normalTrafficAtNightIsLateNight() {
        // Ordinary traffic no longer dodges the late-night window by construction.
        // Running at 03:00 produces genuinely late-night transactions, which is
        // what a real feed does. They do not swamp the alert list, because a lone
        // LATE_NIGHT hit scores 0.20 against an alerting threshold of 0.30 — the
        // threshold does the work the random scatter used to fake, and the rule
        // only matters when it combines with something else.
        Clock threeAm = Clock.fixed(
                ZonedDateTime.of(2026, 3, 12, 3, 15, 0, 0, SAST).toInstant(), SAST);
        TransactionGenerator night = new TransactionGenerator(2000, new Random(42), threeAm);

        // Hour 3 for traffic stamped now, and 01:00–04:59 for the late-hours
        // cardholders whose ordinary spend is placed in that window explicitly.
        // Either way it is the small hours, which is what the rule reacts to.
        assertTrue(java.util.stream.Stream.generate(night::normal).limit(200)
                .allMatch(t -> {
                    int hour = t.timestamp().atZone(SAST).getHour();
                    return hour >= 1 && hour <= 4;
                }));
    }

    @Test
    @DisplayName("the category mix is weighted, not an artefact of the merchant list")
    void categoryMixIsWeighted() {
        // Picking a merchant uniformly made each category's share equal to its
        // merchant count over the total, so groceries landed on exactly 6/36 and
        // the mix described the shop list rather than how people spend.
        Map<String, Long> mix = java.util.stream.Stream.generate(generator::normal)
                .limit(20_000)
                .collect(Collectors.groupingBy(Transaction::merchantCategory, Collectors.counting()));

        double groceries = mix.getOrDefault("groceries", 0L) / 20_000.0;
        double liquor = mix.getOrDefault("liquor", 0L) / 20_000.0;

        // Weights are 22 and 3 of a total of 100.
        assertTrue(groceries > 0.18 && groceries < 0.26, "groceries share was " + groceries);
        assertTrue(liquor > 0.01 && liquor < 0.06, "liquor share was " + liquor);
        assertTrue(groceries > liquor * 3, "the mix should be clearly skewed, not flat");
    }

    @Test
    @DisplayName("normal traffic never uses a watchlisted category")
    void normalTrafficAvoidsWatchlistedCategories() {
        Set<String> watchlisted = Set.of("crypto", "gambling", "forex");
        Set<String> seen = java.util.stream.Stream.generate(generator::normal)
                .limit(1000)
                .map(Transaction::merchantCategory)
                .collect(Collectors.toSet());

        assertTrue(seen.stream().noneMatch(watchlisted::contains),
                "high-risk categories must only appear via deliberate injection");
    }

    @ParameterizedTest
    @EnumSource(FraudPattern.class)
    @DisplayName("every pattern produces at least one transaction")
    void everyPatternProduces(FraudPattern pattern) {
        List<Transaction> batch = generator.fraudulent(pattern);

        assertFalse(batch.isEmpty());
        assertTrue(batch.stream().allMatch(t -> t.id() != null));
        assertTrue(batch.stream().allMatch(t -> t.amount().compareTo(BigDecimal.ZERO) > 0));
    }

    @Test
    @DisplayName("HIGH_VALUE clears the R50 000 threshold")
    void highValueClearsThreshold() {
        assertTrue(generator.fraudulent(FraudPattern.HIGH_VALUE).stream()
                .allMatch(t -> t.amount().compareTo(new BigDecimal("50000")) > 0));
    }

    @Test
    @DisplayName("VELOCITY_BURST is one card, many transactions, inside the window")
    void velocityBurstShape() {
        List<Transaction> burst = generator.fraudulent(FraudPattern.VELOCITY_BURST);

        // Velocity is a property of a sequence, so this pattern has to produce one.
        assertTrue(burst.size() > 5, "burst too small to trip the default limit of 5");
        assertEquals(1, burst.stream().map(Transaction::cardId).distinct().count());

        Duration span = Duration.between(
                burst.get(0).timestamp(), burst.get(burst.size() - 1).timestamp());
        assertTrue(span.toMinutes() < 10, "burst must fall inside the 10 minute window");
    }

    @Test
    @DisplayName("ROUND_AMOUNT is an exact multiple above the floor")
    void roundAmountIsRound() {
        assertTrue(generator.fraudulent(FraudPattern.ROUND_AMOUNT).stream().allMatch(t ->
                t.amount().remainder(new BigDecimal("1000")).signum() == 0
                        && t.amount().compareTo(new BigDecimal("5000")) >= 0));
    }

    @Test
    @DisplayName("CROSS_BORDER leaves South Africa")
    void crossBorderIsForeign() {
        assertTrue(generator.fraudulent(FraudPattern.CROSS_BORDER).stream()
                .noneMatch(t -> SouthAfricanData.HOME_COUNTRY.equals(t.countryCode())));
    }

    @Test
    @DisplayName("LATE_NIGHT falls inside the SAST window")
    void lateNightIsLate() {
        assertTrue(generator.fraudulent(FraudPattern.LATE_NIGHT).stream().allMatch(t -> {
            int hour = t.timestamp().atZone(SAST).getHour();
            return hour >= 1 && hour <= 4;
        }));
    }

    @Test
    @DisplayName("COMPOUND carries several signals at once")
    void compoundIsMultiSignal() {
        Transaction t = generator.fraudulent(FraudPattern.COMPOUND).get(0);

        // This is the shape that demonstrates score accumulation rather than a
        // single rule latching.
        assertTrue(t.amount().compareTo(new BigDecimal("50000")) > 0, "should be high value");
        assertNotEquals(SouthAfricanData.HOME_COUNTRY, t.countryCode(), "should be cross-border");
        assertTrue(Set.of("crypto", "gambling", "forex").contains(t.merchantCategory()),
                "should be a watchlisted category");
        int hour = t.timestamp().atZone(SAST).getHour();
        assertTrue(hour >= 1 && hour <= 4, "should be late night");
    }

    @Test
    @DisplayName("the same seed produces the same stream")
    void deterministicUnderSeed() {
        // Makes any failure in this suite reproducible instead of a flake.
        Transaction a = new TransactionGenerator(100, new Random(7), NOON).normal();
        Transaction b = new TransactionGenerator(100, new Random(7), NOON).normal();

        assertEquals(a.cardId(), b.cardId());
        assertEquals(a.merchantName(), b.merchantName());
        assertEquals(a.amount(), b.amount());
    }
}
