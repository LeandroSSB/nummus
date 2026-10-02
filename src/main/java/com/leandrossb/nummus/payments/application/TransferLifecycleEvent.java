package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed transfer, handed to the outbox in the same transaction.
 * {@code merchantPublicId} is the owning merchant — the delivery audience.
 * A transfer has exactly one lifecycle moment: {@code journalTransactionPublicId}
 * is the balanced entry that moved the money, {@code createdAt} the fact's
 * instant.
 */
public record TransferLifecycleEvent(
    UUID merchantPublicId, String type, UUID publicId, UUID fromAccountPublicId,
    UUID toAccountPublicId, Money amount, UUID journalTransactionPublicId, Instant createdAt) {
}
