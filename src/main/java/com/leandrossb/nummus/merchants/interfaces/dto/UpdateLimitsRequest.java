package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.PaymentLimits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/** A limits replacement over the wire: absent or null field = unlimited. */
public record UpdateLimitsRequest(
    @DecimalMin(value = "0", inclusive = false, message = "maxIntentAmount must be > 0")
    @Digits(integer = 15, fraction = 4) BigDecimal maxIntentAmount,
    @DecimalMin(value = "0", inclusive = false, message = "maxPayoutAmount must be > 0")
    @Digits(integer = 15, fraction = 4) BigDecimal maxPayoutAmount) {

  public PaymentLimits limits() {
    return new PaymentLimits(maxIntentAmount, maxPayoutAmount);
  }
}
