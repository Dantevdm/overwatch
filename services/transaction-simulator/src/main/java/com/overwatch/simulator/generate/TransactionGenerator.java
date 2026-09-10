package com.overwatch.simulator.generate;

import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Transaction;
import com.overwatch.simulator.data.Archetype;
import com.overwatch.simulator.data.Cardholder;
import com.overwatch.simulator.data.CustomerBook;
import com.overwatch.simulator.data.Merchant;
import com.overwatch.simulator.data.SouthAfricanData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * The population: cardholders and the cards they carry.
     *
     * <p>This replaced a flat list of card references. A card is an instrument,
     * and a pattern spread across two of somebody's cards was invisible to a
     * generator whose finest identity was the card — so was any question about
     * whether a person's behaviour is unusual <em>for them</em>, which is most of
     * what a fraud analyst actually asks.
     *
     * <p>The card pool is still the sizing knob: velocity is a property of a card,
     * so the card count against the configured rate is what decides whether that
     * rule discriminates. Holders are derived from cards rather than the reverse,
     * so adding people cannot silently change how velocity behaves.
     */
    private final CustomerBook book;
    private final Random random;

    /**
     * The clock every timestamp comes from, injectable so tests are not at the
     * mercy of what time they happen to run.
     */
    private final Clock clock;

    /** Merchants indexed by category, so a category can be chosen first. */
    private final Map<String, List<Merchant>> byCategory;

    /** Categories in a fixed order, with the running weight total alongside. */
    private final List<String> categories;
    private final int[] cumulativeWeights;
    private final int totalWeight;

    public TransactionGenerator(int cardPoolSize, Random random) {
        this(cardPoolSize, random, Clock.systemDefaultZone());
    }

    public TransactionGenerator(int cardPoolSize, Random random, Clock clock) {
        this.random = random;
        this.clock = clock;
        this.book = new CustomerBook(cardPoolSize, random);

        this.byCategory = SouthAfricanData.MERCHANTS.stream()
                .collect(Collectors.groupingBy(Merchant::category));
        // Sorted rather than in map order: the draw walks this list, so a stable
        // order is what keeps a seeded generator reproducible.
        this.categories = byCategory.keySet().stream().sorted().toList();

        this.cumulativeWeights = new int[categories.size()];
        int running = 0;
        for (int i = 0; i < categories.size(); i++) {
            running += SouthAfricanData.CATEGORY_WEIGHTS.getOrDefault(categories.get(i), 1);
            cumulativeWeights[i] = running;
        }
        this.totalWeight = running;
    }

    /**
     * An ordinary transaction, for whoever the draw lands on.
     *
     * <p>"Ordinary" is per person, not per population. A frequent traveller's
     * ordinary spend is sometimes acquired abroad and a shift worker's is
     * sometimes at three in the morning — both of which trip a rule, and neither
     * of which is fraud. That is not a flaw in the generator; it is the only way
     * the demo can pose the question a fraud system exists to answer, which is
     * whether behaviour is unusual <em>for this cardholder</em> rather than merely
     * unusual. A generator that emitted only the population mean would make every
     * alert correct by construction and the rules impossible to argue about.
     */
    public Transaction normal() {
        Cardholder holder = book.pickHolder(random);
        Merchant merchant = pickMerchant(holder);
        Archetype how = holder.archetype();

        boolean abroad = random.nextDouble() < how.foreignRate();
        boolean atNight = random.nextDouble() < how.nightRate();

        return build(book.pickCard(holder, random), holder, merchant,
                amountFor(merchant, how),
                abroad ? pick(SouthAfricanData.FOREIGN_COUNTRIES) : SouthAfricanData.HOME_COUNTRY,
                atNight ? lateNightTimestamp() : now(),
                channelFor(merchant, how));
    }

    /** Everyone in the book, for the historical backfill and for diagnostics. */
    public List<Cardholder> cardholders() {
        return book.holders();
    }

    /**
     * One historical transaction for a named holder at a given instant.
     *
     * <p>Separate from {@link #normal()} because the backfill needs to choose the
     * subject and the time itself: it is filling in a person's past, so it cannot
     * be drawing a random holder and stamping the result "now".
     */
    public Transaction historical(Cardholder holder, Instant when) {
        Merchant merchant = pickMerchant(holder);
        Archetype how = holder.archetype();
        boolean abroad = random.nextDouble() < how.foreignRate();
        return build(book.pickCard(holder, random), holder, merchant,
                amountFor(merchant, how),
                abroad ? pick(SouthAfricanData.FOREIGN_COUNTRIES) : SouthAfricanData.HOME_COUNTRY,
                when, channelFor(merchant, how));
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
                Cardholder who = book.pickHolder(random);
                Merchant m = pickMerchant(who);
                // Comfortably past the R50 000 threshold.
                BigDecimal amount = randomAmount(55_000, 180_000);
                yield List.of(build(book.pickCard(who, random), who, m, amount,
                        SouthAfricanData.HOME_COUNTRY, now(), Channel.ONLINE));
            }
            case VELOCITY_BURST -> {
                // One card, several transactions inside a couple of minutes: the
                // signature of a stolen card being drained before it is blocked.
                Cardholder who = book.pickHolder(random);
                String card = book.pickCard(who, random);
                Instant now = clock.instant();
                List<Transaction> burst = new ArrayList<>();
                int count = 6 + random.nextInt(4);
                for (int i = 0; i < count; i++) {
                    Merchant m = pickMerchant(who);
                    burst.add(build(card, who, m, amountFor(m, who.archetype()),
                            SouthAfricanData.HOME_COUNTRY,
                            now.minusSeconds((long) (count - i) * 20), Channel.ONLINE));
                }
                yield burst;
            }
            case LATE_NIGHT -> {
                Cardholder who = book.pickHolder(random);
                Merchant m = pickMerchant(who);
                yield List.of(build(book.pickCard(who, random), who, m,
                        amountFor(m, who.archetype()),
                        SouthAfricanData.HOME_COUNTRY, lateNightTimestamp(), Channel.ONLINE));
            }
            case ROUND_AMOUNT -> {
                Cardholder who = book.pickHolder(random);
                Merchant m = pickMerchant(who);
                // Exact multiple of 1 000, at or above the R5 000 floor.
                BigDecimal amount = BigDecimal.valueOf((5 + random.nextInt(25)) * 1000L)
                        .setScale(2, RoundingMode.UNNECESSARY);
                yield List.of(build(book.pickCard(who, random), who, m, amount,
                        SouthAfricanData.HOME_COUNTRY, now(), Channel.ATM));
            }
            case CROSS_BORDER -> {
                Cardholder who = book.pickHolder(random);
                Merchant m = pickMerchant(who);
                yield List.of(build(book.pickCard(who, random), who, m,
                        amountFor(m, who.archetype()),
                        pick(SouthAfricanData.FOREIGN_COUNTRIES),
                        now(), Channel.ONLINE));
            }
            case HIGH_RISK_CATEGORY -> {
                Cardholder who = book.pickHolder(random);
                Merchant m = pick(SouthAfricanData.HIGH_RISK_MERCHANTS);
                yield List.of(build(book.pickCard(who, random), who, m,
                        amountFor(m, who.archetype()),
                        SouthAfricanData.HOME_COUNTRY, now(), Channel.ONLINE));
            }
            case COMPOUND -> {
                // Everything at once: a large, round, foreign, small-hours crypto
                // purchase. This is what a CRITICAL alert looks like, and it shows
                // the score accumulating across rules rather than latching.
                Cardholder who = book.pickHolder(random);
                Merchant m = pick(SouthAfricanData.HIGH_RISK_MERCHANTS);
                BigDecimal amount = BigDecimal.valueOf((55 + random.nextInt(60)) * 1000L)
                        .setScale(2, RoundingMode.UNNECESSARY);
                yield List.of(build(book.pickCard(who, random), who, m, amount,
                        pick(SouthAfricanData.FOREIGN_COUNTRIES),
                        lateNightTimestamp(), Channel.ONLINE));
            }
        };
    }

    // ---- helpers -----------------------------------------------------------

    private Transaction build(String cardId, Cardholder holder, Merchant merchant,
                              BigDecimal amount, String country, Instant when,
                              Channel channel) {
        Map<String, String> metadata = new LinkedHashMap<>();
        // The bank and city now come from the holder rather than being redrawn
        // per transaction. Redrawing them meant one person appeared to bank with
        // four different institutions and live in nine cities, which is not a
        // detail nobody would notice — it is exactly the sort of incoherence that
        // makes a profile screen useless.
        metadata.put("issuingBank", holder.bank());
        metadata.put("city", SouthAfricanData.HOME_COUNTRY.equals(country)
                ? holder.homeCity() : "International");
        metadata.put("archetype", holder.archetype().name());

        return new Transaction(
                UUID.randomUUID(), cardId, holder.id(), holder.name(),
                amount.setScale(2, RoundingMode.HALF_UP),
                Transaction.DEFAULT_CURRENCY,
                merchant.name(), merchant.category(), country, channel, when, metadata);
    }

    /**
     * An amount inside this merchant's range, skewed by who is spending.
     *
     * <p>The exponent on the uniform draw is the skew. Above one it pulls toward
     * the bottom of the range, which is how ordinary spend looks — many small
     * baskets, few large ones. Below one it pulls toward the top, which is what
     * being a high spender means.
     *
     * <p>Per-holder rather than global, because this is what gives the
     * amount-deviation rule something to work with: a card whose own baseline is
     * R2 000 and a card whose baseline is R150 are different subjects, and a rule
     * comparing a transaction to its card's history has nothing to compare
     * against if every card is drawn from one distribution.
     */
    private BigDecimal amountFor(Merchant m, Archetype how) {
        double min = m.minor().doubleValue();
        double max = m.major().doubleValue();
        double skewed = min + (max - min) * Math.pow(random.nextDouble(), how.amountSkew());
        return BigDecimal.valueOf(skewed).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal randomAmount(int min, int max) {
        return BigDecimal.valueOf(min + random.nextInt(max - min))
                .add(BigDecimal.valueOf(random.nextInt(100), 2))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Now, less a fraction of a second.
     *
     * <p>This replaced a method that scattered ordinary traffic uniformly across
     * 07:00–22:00 SAST, which was wrong in three separate ways. It made every
     * time-series chart a flat block fifteen hours wide, because the arrival time
     * carried no information. It made narrow windows nearly empty, since only
     * about a nine-hundredth of traffic landed in any given minute. And because
     * the hour was drawn without reference to the clock, a transaction generated
     * at 21:21 could be stamped 21:59 — 47 rows in a sample of 1 316 were dated
     * in the future, which no real payment system produces.
     *
     * <p>The shape of a day now comes from <em>when transactions are emitted</em>
     * rather than from scattering their timestamps, which is both the honest
     * mechanism and the one that makes a five-minute window meaningful.
     *
     * <p>The sub-second offset keeps transactions inside one tick from sharing an
     * identical instant, so ordering is total rather than arbitrary.
     */
    private Instant now() {
        return clock.instant().minusMillis(random.nextInt(1000));
    }

    /**
     * The most recent 01:00–04:59 SAST window.
     *
     * <p>Deliberately back-dated: an injected late-night pattern has to trip the
     * rule whoever runs the demo and whenever they run it. Winding back a day
     * when that window has not yet arrived today is what stops a 00:30 demo from
     * producing transactions dated four hours into the future.
     */
    private Instant lateNightTimestamp() {
        ZonedDateTime when = ZonedDateTime.now(clock.withZone(SAST))
                .withHour(1 + random.nextInt(4))
                .withMinute(random.nextInt(60))
                .withSecond(random.nextInt(60));
        if (when.toInstant().isAfter(clock.instant())) {
            when = when.minusDays(1);
        }
        return when.toInstant();
    }

    /**
     * Channel that fits both the merchant and the person.
     *
     * <p>The merchant constrains it first — an ATM withdrawal is not an online
     * purchase whoever makes it — and where the merchant leaves a choice, the
     * holder's preference decides. Someone who lives on their phone should not
     * show up as eighty per cent card-present just because the population does.
     */
    private Channel channelFor(Merchant m, Archetype how) {
        return switch (m.category()) {
            case "ecommerce", "telecoms", "utilities" -> Channel.ONLINE;
            case "cash" -> Channel.ATM;
            case "transport" -> Channel.MOBILE;
            // 20% card-not-present for an ordinary holder, rising with the bias.
            default -> random.nextDouble() < 0.2 + how.onlineBias() * 0.6
                    ? Channel.ONLINE : Channel.POS;
        };
    }

    /**
     * A merchant, drawn by category weight rather than uniformly.
     *
     * <p>Uniform selection over the merchant list gave each category a share equal
     * to its merchant count over the total, so the mix described the shop list
     * rather than the economy — and adding one shop name silently reweighted
     * everything. Choosing the category first breaks that coupling.
     */
    private Merchant pickMerchant(Cardholder holder) {
        // An online-first holder's spend skews toward the categories that are
        // online by nature. Applied as a re-draw rather than a second weight
        // table: one biased coin is easier to reason about than two overlapping
        // distributions, and it leaves the population mix unchanged for everyone
        // else.
        if (random.nextDouble() < holder.archetype().onlineBias() * 0.5) {
            List<Merchant> online = byCategory.get("ecommerce");
            if (online != null && !online.isEmpty()) return pick(online);
        }
        int draw = random.nextInt(totalWeight);
        for (int i = 0; i < cumulativeWeights.length; i++) {
            if (draw < cumulativeWeights[i]) {
                return pick(byCategory.get(categories.get(i)));
            }
        }
        // Unreachable: draw < totalWeight and the last cumulative weight is the
        // total. Falling back rather than throwing, because a generator failing
        // on an arithmetic edge would take the stream down for nothing.
        return pick(byCategory.get(categories.get(categories.size() - 1)));
    }

    private <T> T pick(List<T> from) {
        return from.get(random.nextInt(from.size()));
    }

}
