package com.overwatch.simulator.generate;

import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Transaction;
import com.overwatch.simulator.data.Merchant;
import com.overwatch.simulator.data.SouthAfricanData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Produces realistic South African card transactions, and — on demand — ones
 * deliberately shaped to trip a specific rule.
 *
 * <p>Free of Spring by design: the generator takes a {@link Random} it is given,
 * so a seeded instance produces the same stream every time and its behaviour can
 * be asserted in a plain unit test rather than observed and hoped about.
 */
public class TransactionGenerator {

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    /** Cards in circulation. A fixed pool is what makes velocity meaningful. */
    private final List<String> cardPool;
    private final Random random;

    public TransactionGenerator(int cardPoolSize, Random random) {
        this.random = random;
        this.cardPool = new ArrayList<>(cardPoolSize);
        for (int i = 0; i < cardPoolSize; i++) {
            // Tokenised reference, never anything resembling a real PAN.
            cardPool.add("card-%05d".formatted(i));
        }
    }

    /** An ordinary transaction: local merchant, sensible amount, business hours. */
    public Transaction normal() {
        Merchant merchant = pick(SouthAfricanData.MERCHANTS);
        return build(pickCard(), merchant, amountFor(merchant),
                SouthAfricanData.HOME_COUNTRY, businessHoursTimestamp(), channelFor(merchant));
    }

    /**
     * One or more transactions shaped to trip a rule. Most patterns produce a
     * single transaction; {@link FraudPattern#VELOCITY_BURST} produces several on
     * the same card, because velocity is a property of a sequence rather than of
     * any one transaction.
     */
    public List<Transaction> fraudulent(FraudPattern pattern) {
        return switch (pattern) {
            case HIGH_VALUE -> {
                Merchant m = pick(SouthAfricanData.MERCHANTS);
                // Comfortably past the R50 000 threshold.
                BigDecimal amount = randomAmount(55_000, 180_000);
                yield List.of(build(pickCard(), m, amount, SouthAfricanData.HOME_COUNTRY,
                        businessHoursTimestamp(), Channel.ONLINE));
            }
            case VELOCITY_BURST -> {
                // One card, several transactions inside a couple of minutes: the
                // signature of a stolen card being drained before it is blocked.
                String card = pickCard();
                Instant now = Instant.now();
                List<Transaction> burst = new ArrayList<>();
                int count = 6 + random.nextInt(4);
                for (int i = 0; i < count; i++) {
                    Merchant m = pick(SouthAfricanData.MERCHANTS);
                    burst.add(build(card, m, amountFor(m), SouthAfricanData.HOME_COUNTRY,
                            now.minusSeconds((long) (count - i) * 20), Channel.ONLINE));
                }
                yield burst;
            }
            case LATE_NIGHT -> {
                Merchant m = pick(SouthAfricanData.MERCHANTS);
                yield List.of(build(pickCard(), m, amountFor(m),
                        SouthAfricanData.HOME_COUNTRY, lateNightTimestamp(), Channel.ONLINE));
            }
            case ROUND_AMOUNT -> {
                Merchant m = pick(SouthAfricanData.MERCHANTS);
                // Exact multiple of 1 000, at or above the R5 000 floor.
                BigDecimal amount = BigDecimal.valueOf((5 + random.nextInt(25)) * 1000L)
                        .setScale(2, RoundingMode.UNNECESSARY);
                yield List.of(build(pickCard(), m, amount, SouthAfricanData.HOME_COUNTRY,
                        businessHoursTimestamp(), Channel.ATM));
            }
            case CROSS_BORDER -> {
                Merchant m = pick(SouthAfricanData.MERCHANTS);
                yield List.of(build(pickCard(), m, amountFor(m),
                        pick(SouthAfricanData.FOREIGN_COUNTRIES),
                        businessHoursTimestamp(), Channel.ONLINE));
            }
            case HIGH_RISK_CATEGORY -> {
                Merchant m = pick(SouthAfricanData.HIGH_RISK_MERCHANTS);
                yield List.of(build(pickCard(), m, amountFor(m),
                        SouthAfricanData.HOME_COUNTRY, businessHoursTimestamp(), Channel.ONLINE));
            }
            case COMPOUND -> {
                // Everything at once: a large, round, foreign, small-hours crypto
                // purchase. This is what a CRITICAL alert looks like, and it shows
                // the score accumulating across rules rather than latching.
                Merchant m = pick(SouthAfricanData.HIGH_RISK_MERCHANTS);
                BigDecimal amount = BigDecimal.valueOf((55 + random.nextInt(60)) * 1000L)
                        .setScale(2, RoundingMode.UNNECESSARY);
                yield List.of(build(pickCard(), m, amount,
                        pick(SouthAfricanData.FOREIGN_COUNTRIES),
                        lateNightTimestamp(), Channel.ONLINE));
            }
        };
    }

    // ---- helpers -----------------------------------------------------------

    private Transaction build(String cardId, Merchant merchant, BigDecimal amount,
                              String country, Instant when, Channel channel) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("issuingBank", pick(SouthAfricanData.BANKS));
        metadata.put("city", SouthAfricanData.HOME_COUNTRY.equals(country)
                ? pick(SouthAfricanData.CITIES) : "International");

        return new Transaction(
                UUID.randomUUID(), cardId,
                amount.setScale(2, RoundingMode.HALF_UP),
                Transaction.DEFAULT_CURRENCY,
                merchant.name(), merchant.category(), country, channel, when, metadata);
    }

    /** An amount inside this merchant's normal range, skewed toward the low end. */
    private BigDecimal amountFor(Merchant m) {
        double min = m.minor().doubleValue();
        double max = m.major().doubleValue();
        // Squaring the uniform draw pulls the distribution toward smaller values,
        // which is how real spend actually looks: many small, few large.
        double skewed = min + (max - min) * Math.pow(random.nextDouble(), 2);
        return BigDecimal.valueOf(skewed).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal randomAmount(int min, int max) {
        return BigDecimal.valueOf(min + random.nextInt(max - min))
                .add(BigDecimal.valueOf(random.nextInt(100), 2))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Between 07:00 and 22:00 SAST, so ordinary traffic never trips LATE_NIGHT. */
    private Instant businessHoursTimestamp() {
        ZonedDateTime now = ZonedDateTime.now(SAST);
        int hour = 7 + random.nextInt(15);
        return now.withHour(hour)
                .withMinute(random.nextInt(60))
                .withSecond(random.nextInt(60))
                .toInstant();
    }

    /** Between 01:00 and 04:59 SAST — inside the configured window. */
    private Instant lateNightTimestamp() {
        ZonedDateTime now = ZonedDateTime.now(SAST);
        return now.withHour(1 + random.nextInt(4))
                .withMinute(random.nextInt(60))
                .withSecond(random.nextInt(60))
                .toInstant();
    }

    /** Channel that fits the merchant, so the data stays internally consistent. */
    private Channel channelFor(Merchant m) {
        return switch (m.category()) {
            case "ecommerce", "telecoms", "utilities" -> Channel.ONLINE;
            case "cash" -> Channel.ATM;
            case "transport" -> Channel.MOBILE;
            default -> random.nextInt(10) < 8 ? Channel.POS : Channel.ONLINE;
        };
    }

    private <T> T pick(List<T> from) {
        return from.get(random.nextInt(from.size()));
    }

    private String pickCard() {
        return cardPool.get(random.nextInt(cardPool.size()));
    }
}
