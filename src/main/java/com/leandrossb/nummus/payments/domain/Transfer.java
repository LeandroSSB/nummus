package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A merchant's internal transfer — instant book movement between two of the
 * merchant's own payment accounts. A transfer is a single-state fact: no
 * lifecycle, no status — {@code journalTransactionPublicId} IS the record,
 * the one balanced entry that debited {@code fromAccountPublicId} and
 * credited {@code toAccountPublicId} in the same commit as this row.
 * {@code merchantPublicId} is denormalized (the webhook-tables precedent) so
 * listings and ownership scope without the accounts seam.
 */
public record Transfer(
    UUID publicId,
    UUID merchantPublicId,
    UUID fromAccountPublicId,
    UUID toAccountPublicId,
    Money amount,
    UUID journalTransactionPublicId,
    Instant createdAt) {
}
