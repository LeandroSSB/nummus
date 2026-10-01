package com.leandrossb.nummus.merchants.application;

import java.math.BigDecimal;

/**
 * Per-merchant caps on single money-moving operations. A null field means
 * unlimited — the default. Comparisons are inclusive: an amount equal to the
 * cap passes; only strictly greater rejects.
 */
public record PaymentLimits(BigDecimal maxIntentAmount, BigDecimal maxPayoutAmount) {

  public static PaymentLimits unlimited() {
    return new PaymentLimits(null, null);
  }
}
