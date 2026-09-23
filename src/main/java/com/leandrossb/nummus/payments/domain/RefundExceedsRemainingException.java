package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * Thrown when a refund request exceeds the intent's refundable remainder —
 * the intent amount minus the refunds still holding or already returning
 * funds. Carries the derived remainder the decision was made against and the
 * requested amount, both read under the intent's account row lock.
 */
public class RefundExceedsRemainingException extends RuntimeException {

  private final UUID intentPublicId;
  private final Money remaining;
  private final Money requested;

  public RefundExceedsRemainingException(UUID intentPublicId, Money remaining, Money requested) {
    super("refund exceeds the remaining amount of intent " + intentPublicId + ": remaining "
        + remaining.amount().toPlainString() + " " + remaining.currency().getCurrencyCode()
        + ", requested " + requested.amount().toPlainString() + " "
        + requested.currency().getCurrencyCode());
    this.intentPublicId = intentPublicId;
    this.remaining = remaining;
    this.requested = requested;
  }

  public UUID intentPublicId() {
    return intentPublicId;
  }

  public Money remaining() {
    return remaining;
  }

  public Money requested() {
    return requested;
  }
}
