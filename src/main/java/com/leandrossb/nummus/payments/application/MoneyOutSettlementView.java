package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A settled payout or refund as seen by conciliation — the money-out
 *  settlement record, keyed by the network instruction it executed. */
public record MoneyOutSettlementView(
    UUID internalPublicId, UUID networkInstructionPublicId,
    Money amount, Instant settledAt) {
}
