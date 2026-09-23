package com.leandrossb.nummus.psp_simulator.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * Thrown when a refund instruction would push the charge's total refunded
 * past its amount. Carries the refundable remainder the decision was made
 * against — the charge amount minus every existing refund, PENDING ones
 * included — and the requested amount.
 */
public class RefundExceedsChargeException extends RuntimeException {

  private final UUID chargePublicId;
  private final Money remaining;
  private final Money requested;

  public RefundExceedsChargeException(UUID chargePublicId, Money remaining, Money requested) {
    super("refund exceeds charge " + chargePublicId + ": remaining "
        + remaining.amount().toPlainString() + " " + remaining.currency().getCurrencyCode()
        + ", requested " + requested.amount().toPlainString() + " "
        + requested.currency().getCurrencyCode());
    this.chargePublicId = chargePublicId;
    this.remaining = remaining;
    this.requested = requested;
  }

  public UUID chargePublicId() {
    return chargePublicId;
  }

  public Money remaining() {
    return remaining;
  }

  public Money requested() {
    return requested;
  }
}
