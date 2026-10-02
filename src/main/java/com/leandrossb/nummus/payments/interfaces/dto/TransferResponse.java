package com.leandrossb.nummus.payments.interfaces.dto;

import com.leandrossb.nummus.payments.domain.Transfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * REST view of an internal transfer. UUIDs only. A transfer is a single-state
 * fact: there is no status to expose — {@code journalTransactionId} IS the
 * record, the one balanced entry that moved the funds in the same commit as
 * the row this view reads from.
 */
public record TransferResponse(
    UUID publicId,
    UUID fromAccountId,
    UUID toAccountId,
    BigDecimal amount,
    String currency,
    UUID journalTransactionId,
    Instant createdAt) {

  public static TransferResponse from(Transfer transfer) {
    return new TransferResponse(transfer.publicId(), transfer.fromAccountPublicId(),
        transfer.toAccountPublicId(), transfer.amount().amount(),
        transfer.amount().currency().getCurrencyCode(),
        transfer.journalTransactionPublicId(), transfer.createdAt());
  }
}
