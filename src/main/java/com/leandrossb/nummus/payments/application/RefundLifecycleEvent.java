package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed refund transition, handed to the outbox in the same transaction.
 * {@code merchantPublicId} is the owning merchant — the delivery audience.
 * {@code intentPublicId} and {@code accountPublicId} place the refund: the row
 * is intent-scoped, so the account resolves through the intent.
 * {@code networkRefundPublicId} identifies the network-side charge refund;
 * {@code journalTransactionPublicId} is the transition's journal link — the
 * execution on SETTLED, the return on FAILED and EXPIRED. There are no fee
 * facts: processing fees are retained, never recorded on the refund.
 */
public record RefundLifecycleEvent(
    UUID merchantPublicId, String type, UUID publicId, UUID intentPublicId, UUID accountPublicId,
    Money amount, String status, UUID networkRefundPublicId, Instant settledAt,
    UUID journalTransactionPublicId) {
}
