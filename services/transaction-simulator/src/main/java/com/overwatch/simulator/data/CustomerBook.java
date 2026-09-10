package com.overwatch.simulator.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The population: cardholders, their cards, and how often each of them spends.
 *
 * <p>Built once from a {@link Random} the caller supplies, so a seeded book is
 * the same population every run — which is what lets a demo script name a
 * customer, and what makes the historical backfill idempotent across restarts.
 *
 * <p>The card pool is still the sizing knob, and deliberately so. Velocity is a
 * property of a card, and the rule fires when a card exceeds a count inside a
 * window — so the number of cards, against the configured rate, is what decides
 * whether that rule discriminates or fires on everything. Cardholders are then
 * derived from the cards rather than the other way round, so adding people cannot
 * silently change how the velocity rule behaves.
 *
 * <p>Activity weighting is deliberately mild — the heaviest users see roughly two
 * and a half times the mean, not fifty times. A steeper curve would concentrate
 * live traffic onto a few cards and start tripping velocity on ordinary
 * behaviour, which is exactly the failure this system has already had once. Depth
 * for a 360 view comes from backfilled history instead, which is the honest place
 * for it: real depth is months of spending, not a busy minute.
 */
public class CustomerBook {

    /** Cards per holder. Most people carry one; a few carry three. */
    private static final int[] CARDS_PER_HOLDER = {1, 1, 1, 1, 2, 2, 2, 3};

    private final List<Cardholder> holders;

    /** Card to holder, so a card resolves to its person in constant time. */
    private final Map<String, Cardholder> holderByCard;

    /** Cards in a fixed order — the draw walks this, so the order must be stable. */
    private final List<String> cards;

    /** Running total of activity weight, for a weighted draw over holders. */
    private final double[] cumulativeWeight;
    private final double totalWeight;

    public CustomerBook(int cardPoolSize, Random random) {
        this.holders = new ArrayList<>();
        this.cards = new ArrayList<>(cardPoolSize);
        this.holderByCard = new HashMap<>(cardPoolSize * 2);

        int cardIndex = 0;
        int holderIndex = 0;
        while (cardIndex < cardPoolSize) {
            int count = Math.min(CARDS_PER_HOLDER[random.nextInt(CARDS_PER_HOLDER.length)],
                    cardPoolSize - cardIndex);

            List<String> theirCards = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                // Tokenised reference, never anything resembling a real PAN.
                String card = "card-%05d".formatted(cardIndex++);
                theirCards.add(card);
                cards.add(card);
            }

            Cardholder holder = new Cardholder(
                    "cust-%05d".formatted(holderIndex++),
                    name(random),
                    pick(SouthAfricanData.CITIES, random),
                    pick(SouthAfricanData.BANKS, random),
                    List.copyOf(theirCards),
                    archetype(random),
                    // A light long tail: most people near the mean, a minority
                    // two to three times as active. Deliberately not a power law.
                    0.6 + Math.pow(random.nextDouble(), 2) * 2.2);

            holders.add(holder);
            theirCards.forEach(card -> holderByCard.put(card, holder));
        }

        this.cumulativeWeight = new double[holders.size()];
        double running = 0;
        for (int i = 0; i < holders.size(); i++) {
            running += holders.get(i).activityWeight();
            cumulativeWeight[i] = running;
        }
        this.totalWeight = running;
    }

    /** Every cardholder, in a stable order. */
    public List<Cardholder> holders() {
        return List.copyOf(holders);
    }

    public int cardCount() {
        return cards.size();
    }

    /**
     * A cardholder, drawn by activity weight.
     *
     * <p>Binary search rather than a linear walk: this runs on every generated
     * transaction, and at the rates this thing is driven to, a scan over six
     * hundred holders per transaction is measurable for no reason.
     */
    public Cardholder pickHolder(Random random) {
        double draw = random.nextDouble() * totalWeight;
        int low = 0;
        int high = cumulativeWeight.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cumulativeWeight[mid] < draw) low = mid + 1;
            else high = mid;
        }
        return holders.get(low);
    }

    /** One of this holder's cards, uniformly — people use their cards fairly evenly. */
    public String pickCard(Cardholder holder, Random random) {
        return holder.cards().get(random.nextInt(holder.cards().size()));
    }

    /**
     * Who holds this card.
     *
     * <p>Returns null for a card outside the pool, which is not defensive
     * padding: the injected fraud patterns and hand-published transactions can
     * name any card, and a generator that threw here would take the stream down
     * over an unknown card reference.
     */
    public Cardholder holderOf(String cardId) {
        return holderByCard.get(cardId);
    }

    private static String name(Random random) {
        // First name and surname drawn independently, so the result is a person
        // who does not exist rather than one who might.
        return pick(SouthAfricanData.FIRST_NAMES, random)
                + " " + pick(SouthAfricanData.SURNAMES, random);
    }

    private static Archetype archetype(Random random) {
        int total = 0;
        for (Archetype a : Archetype.values()) total += a.share();
        int draw = random.nextInt(total);
        int running = 0;
        for (Archetype a : Archetype.values()) {
            running += a.share();
            if (draw < running) return a;
        }
        return Archetype.EVERYDAY;
    }

    private static <T> T pick(List<T> from, Random random) {
        return from.get(random.nextInt(from.size()));
    }
}
