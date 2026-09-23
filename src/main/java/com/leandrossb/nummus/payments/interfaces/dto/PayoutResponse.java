package com.leandrossb.nummus.payments.interfaces.dto;

import com.leandrossb.nummus.payments.domain.Payout;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * REST view of a payout. UUIDs only; settledAt and fee appear post-settlement.
 * The fee is the settled fact — never a quote: null until the execution charges
 * it, then exactly what was charged.
 */
public record PayoutResponse(
    UUID publicId,
    UUID accountId,
    BigDecimal amount,
    String currency,
    String destinationBankKey,
    UUID bankAccountId,
    String status,
    UUID transferId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt,
    BigDecimal fee,
    UUID reservationTransactionId) {

  public static PayoutResponse from(Payout payout) {
    return new PayoutResponse(payout.publicId(), payout.accountPublicId(),
        payout.amount().amount(), payout.amount().currency().getCurrencyCode(),
        payout.destinationBankKey(), payout.bankAccountPublicId(), payout.status().name(),
        payout.transferPublicId(),
        payout.expiresAt(), payout.createdAt(), payout.settledAt(),
        payout.feeAmount() == null ? null : payout.feeAmount().amount(),
        payout.requestTransactionPublicId());
  }
}
