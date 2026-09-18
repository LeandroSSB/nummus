package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A completed intent transition, handed to the outbox in the same transaction. */
public record IntentLifecycleEvent(
    String type, UUID publicId, UUID accountPublicId, Money amount, String status,
    UUID chargePublicId, Instant settledAt, UUID journalTransactionPublicId) {
}
