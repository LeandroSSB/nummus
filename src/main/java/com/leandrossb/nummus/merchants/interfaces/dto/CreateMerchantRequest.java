package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.FeeSchedule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record CreateMerchantRequest(
    @NotBlank(message = "name must not be blank") String name,
    @Valid FeeRequest fee) {

  /** The schedule to onboard with; absent means the zero schedule. */
  public FeeSchedule feeSchedule() {
    return fee == null ? FeeSchedule.ZERO
        : new FeeSchedule(fee.rate(), fee.fixedAmount(),
            fee.payoutFixedAmount() == null ? BigDecimal.ZERO : fee.payoutFixedAmount());
  }
}
