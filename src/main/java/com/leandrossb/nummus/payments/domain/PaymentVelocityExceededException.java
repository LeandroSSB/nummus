package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * Thrown when an intent attempt pushes the merchant's trailing-24h intent
 * volume past its daily cap. Carries the window's usage before the attempt,
 * the requested amount, and the cap the decision was made against; the
 * comparison is inclusive, so reaching here means strictly over.
 */
public class PaymentVelocityExceededException extends RuntimeException {

  private final UUID merchantPublicId;
  private final Money windowUsage;
  private final Money requested;
  private final Money cap;

  public PaymentVelocityExceededException(UUID merchantPublicId, Money windowUsage,
      Money requested, Money cap) {
    super("daily intent volume exceeded for merchant " + merchantPublicId + ": window "
        + windowUsage.amount().toPlainString() + " "
        + windowUsage.currency().getCurrencyCode()
        + " + requested " + requested.amount().toPlainString() + " "
        + requested.currency().getCurrencyCode()
        + " > cap " + cap.amount().toPlainString() + " " + cap.currency().getCurrencyCode());
    this.merchantPublicId = merchantPublicId;
    this.windowUsage = windowUsage;
    this.requested = requested;
    this.cap = cap;
  }

  public UUID merchantPublicId() {
    return merchantPublicId;
  }

  public Money windowUsage() {
    return windowUsage;
  }

  public Money requested() {
    return requested;
  }

  public Money cap() {
    return cap;
  }
}
