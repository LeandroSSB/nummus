package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A settled payment intent as seen by conciliation — the settlement record. */
public record SettlementView(
    UUID intentPublicId, UUID accountPublicId, UUID chargePublicId,
    Money amount, Instant settledAt, UUID journalTransactionPublicId) {
}
