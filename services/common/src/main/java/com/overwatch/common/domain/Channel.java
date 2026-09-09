package com.overwatch.common.domain;

/**
 * How the card was presented. Channel matters to fraud scoring: a card-not-present
 * online transaction carries different risk from a chip-and-pin purchase in store.
 */
public enum Channel {
    POS,
    ONLINE,
    ATM,
    MOBILE
}
