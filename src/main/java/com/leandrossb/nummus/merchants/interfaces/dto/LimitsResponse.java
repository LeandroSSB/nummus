package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.PaymentLimits;
import java.math.BigDecimal;

/** REST view of a merchant's payment limits; a null field means unlimited. */
public record LimitsResponse(BigDecimal maxIntentAmount, BigDecimal maxPayoutAmount) {

  public static LimitsResponse from(PaymentLimits limits) {
    return new LimitsResponse(limits.maxIntentAmount(), limits.maxPayoutAmount());
  }
}
