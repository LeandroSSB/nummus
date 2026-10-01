package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * Thrown when a single money-moving operation exceeds the merchant's cap for
 * it. Carries the requested amount and the cap the decision was made against;
 * the comparison is inclusive, so reaching here means strictly over.
 */
public class PaymentLimitExceededException extends RuntimeException {

  private final UUID merchantPublicId;
  private final Money requested;
  private final Money cap;

  public PaymentLimitExceededException(UUID merchantPublicId, Money requested, Money cap) {
    super("payment limit exceeded for merchant " + merchantPublicId + ": requested "
        + requested.amount().toPlainString() + " " + requested.currency().getCurrencyCode()
        + ", cap " + cap.amount().toPlainString() + " " + cap.currency().getCurrencyCode());
    this.merchantPublicId = merchantPublicId;
    this.requested = requested;
    this.cap = cap;
  }

  public UUID merchantPublicId() {
    return merchantPublicId;
  }

  public Money requested() {
    return requested;
  }

  public Money cap() {
    return cap;
  }
}
