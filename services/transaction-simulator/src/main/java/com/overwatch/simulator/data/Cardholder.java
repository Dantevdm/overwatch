package com.overwatch.simulator.data;

import java.util.List;

/**
 * A person, and the cards they carry.
 *
 * <p>A card is an instrument. People hold more than one, and a pattern spread
 * across two of somebody's cards is invisible to a system whose finest identity
 * is the card — which is the whole reason this type exists.
 *
 * @param id             tokenised cardholder reference, never a national ID
 * @param name           synthetic display name, combined from independent parts
 * @param homeCity       where their ordinary spend happens
 * @param bank           issuing bank, shared across their cards
 * @param cards          the cards they carry, one to three
 * @param archetype      how they spend
 * @param activityWeight relative share of traffic, so the population has heavy
 *                       and light users rather than six hundred identical ones
 */
public record Cardholder(
        String id,
        String name,
        String homeCity,
        String bank,
        List<String> cards,
        Archetype archetype,
        double activityWeight
) {
}
