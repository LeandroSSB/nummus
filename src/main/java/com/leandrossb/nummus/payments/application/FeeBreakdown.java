package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;

/** The charged fee and the merchant's net for one settlement. */
public record FeeBreakdown(Money fee, Money net) {
}
