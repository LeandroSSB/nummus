package com.leandrossb.nummus.merchants.application;

import java.math.BigDecimal;

/**
 * Per-merchant caps on single money-moving operations plus a velocity cap.
 * A null field means unlimited — the default. Comparisons are inclusive: an
 * amount equal to the cap passes; only strictly greater rejects.
 * {@code maxDailyIntentVolume} bounds the gross amount of intents attempted
 * over the trailing 24 hours (attempts of any status count).
 */
public record PaymentLimits(BigDecimal maxIntentAmount, BigDecimal maxPayoutAmount,
    BigDecimal maxDailyIntentVolume) {

  public static PaymentLimits unlimited() {
    return new PaymentLimits(null, null, null);
  }
}
