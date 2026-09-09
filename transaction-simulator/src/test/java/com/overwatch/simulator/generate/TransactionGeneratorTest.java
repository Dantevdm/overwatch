package com.overwatch.simulator.generate;

import com.overwatch.common.domain.Transaction;
import com.overwatch.simulator.data.SouthAfricanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TransactionGenerator")
class TransactionGeneratorTest {

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    /** Seeded, so a failure here is reproducible rather than a flake. */
    private final TransactionGenerator generator = new TransactionGenerator(2000, new Random(42));

    @Test
    @DisplayName("normal traffic is local, in ZAR, and plausibly priced")
    void normalTrafficLooksReal() {
        List<Transaction> sample = java.util.stream.Stream
                .generate(generator::normal).limit(500).toList();

        assertTrue(sample.stream().allMatch(t -> "ZAR".equals(t.currency())));
        assertTrue(sample.stream().allMatch(t -> SouthAfricanData.HOME_COUNTRY.equals(t.countryCode())));
        assertTrue(sample.stream().allMatch(t -> t.amount().compareTo(BigDecimal.ZERO) > 0));

        // Skewed toward small amounts, as real card spend is. A mean in the tens of
        // thousands would mean the "high value" rule has nothing to distinguish.
        double mean = sample.stream().mapToDouble(t -> t.amount().doubleValue()).average().orElseThrow();
        assertTrue(mean > 100 && mean < 3000, "implausible mean spend: R" + mean);
    }

    @Test
    @DisplayName("normal traffic never lands in the small hours")
    void normalTrafficAvoidsTheLateNightWindow() {
        // If ordinary traffic drifted into 01:00-04:59 SAST, the late-night rule
        // would fire constantly and the alert list would be meaningless.
        boolean anyLateNight = java.util.stream.Stream.generate(generator::normal)
                .limit(1000)
                .anyMatch(t -> {
                    int hour = t.timestamp().atZone(SAST).getHour();
                    return hour >= 1 && hour <= 4;
                });
        assertFalse(anyLateNight, "normal traffic must stay outside the late-night window");
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
        Transaction a = new TransactionGenerator(100, new Random(7)).normal();
        Transaction b = new TransactionGenerator(100, new Random(7)).normal();

        assertEquals(a.cardId(), b.cardId());
        assertEquals(a.merchantName(), b.merchantName());
        assertEquals(a.amount(), b.amount());
    }
}
