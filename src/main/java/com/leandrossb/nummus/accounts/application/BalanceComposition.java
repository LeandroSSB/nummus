package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.ledger.domain.Money;

/**
 * The three-figure answer to "how much does this account hold": booked funds
 * plus the in-flight context around them. pendingIncoming is informational —
 * not yet in balance. reservedOutgoing is informational — already deducted
 * from balance by its reservation posting. What a new payout may reserve is
 * balance alone.
 */
public record BalanceComposition(Money balance, Money pendingIncoming, Money reservedOutgoing) {}
