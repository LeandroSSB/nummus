package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed payout transition, handed to the outbox in the same transaction.
 * {@code merchantPublicId} is the owning merchant — the delivery audience.
 * {@code transferPublicId} and {@code destinationBankKey} identify the outbound
 * network transfer; {@code journalTransactionPublicId} is the transition's
 * journal link — the execution on SETTLED, the return on FAILED and EXPIRED.
 * {@code fee} and {@code netAmount} are the execution's fee facts — present
 * only on SETTLED events, null on EXPIRED and FAILED.
 */
public record PayoutLifecycleEvent(
    UUID merchantPublicId, String type, UUID publicId, UUID accountPublicId, Money amount,
    String status, UUID transferPublicId, String destinationBankKey, Instant settledAt,
    UUID journalTransactionPublicId, Money fee, Money netAmount) {
}
