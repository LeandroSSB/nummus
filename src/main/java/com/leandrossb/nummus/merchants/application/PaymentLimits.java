package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.ledger.domain.Money;

/**
 * Per-merchant caps on single money-moving operations. A null field means
 * unlimited — the default. Comparisons are inclusive: an amount equal to the
 * cap passes; only strictly greater rejects.
 */
public record PaymentLimits(Money maxIntentAmount, Money maxPayoutAmount) {

  public static PaymentLimits unlimited() {
    return new PaymentLimits(null, null);
  }
}
