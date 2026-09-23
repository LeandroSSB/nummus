package com.leandrossb.nummus.payments.interfaces.dto;

import com.leandrossb.nummus.payments.domain.Refund;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * REST view of a refund. UUIDs only; settledAt appears post-settlement. The
 * hold link is the request's journal anchor — the reserve that holds the funds
 * aside; the execute/return links stay internal to the lifecycle.
 */
public record RefundResponse(
    UUID publicId,
    UUID intentId,
    BigDecimal amount,
    String currency,
    String status,
    UUID networkRefundId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt,
    UUID holdTransactionId) {

  public static RefundResponse from(Refund refund) {
    return new RefundResponse(refund.publicId(), refund.intentPublicId(),
        refund.amount().amount(), refund.amount().currency().getCurrencyCode(),
        refund.status().name(), refund.networkRefundPublicId(), refund.expiresAt(),
        refund.createdAt(), refund.settledAt(), refund.holdTransactionPublicId());
  }
}
