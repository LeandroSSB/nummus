package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed intent transition, handed to the outbox in the same transaction.
 * {@code merchantPublicId} is the owning merchant — the delivery audience.
 * {@code fee} and {@code netAmount} are the settlement's fee facts — present only
 * on SETTLED events, null on EXPIRED and FAILED.
 */
public record IntentLifecycleEvent(
    UUID merchantPublicId, String type, UUID publicId, UUID accountPublicId, Money amount,
    String status, UUID chargePublicId, Instant settledAt, UUID journalTransactionPublicId,
    Money fee, Money netAmount) {
}
