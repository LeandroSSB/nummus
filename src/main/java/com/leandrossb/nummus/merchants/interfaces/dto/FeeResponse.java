package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.FeeSchedule;
import java.math.BigDecimal;

/** REST view of a merchant's fee schedule. */
public record FeeResponse(BigDecimal rate, BigDecimal fixedAmount, BigDecimal payoutFixedAmount) {

  public static FeeResponse from(FeeSchedule fee) {
    return new FeeResponse(fee.rate(), fee.fixedAmount(), fee.payoutFixedAmount());
  }
}
