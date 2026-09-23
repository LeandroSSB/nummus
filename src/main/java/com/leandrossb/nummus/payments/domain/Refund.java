package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A settled payment's refund (money-back). Scoped to the payment intent and the
 * network-side refund by public UUID only; the journal links carry the two-phase
 * hold — {@code holdTransactionPublicId} posts the reserve that holds the funds
 * aside, {@code executeTransactionPublicId} settles them out on success, and
 * {@code returnTransactionPublicId} releases the reserve back to the account on
 * expiry or failure. Refunds carry no fee fact: processing fees are retained,
 * never recorded on the refund.
 */
public record Refund(
    UUID publicId,
    UUID intentPublicId,
    Money amount,
    RefundStatus status,
    UUID networkRefundPublicId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt,
    UUID holdTransactionPublicId,
    UUID executeTransactionPublicId,
    UUID returnTransactionPublicId) {
}
