package com.leandrossb.nummus.merchants.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A merchant's fee schedule: a fraction of the gross amount plus a fixed BRL
 * amount per settlement, and a fixed BRL amount charged per payout. Plain
 * decimals, never Money — the merchants module stays self-contained and never
 * depends on the ledger's types.
 */
public record FeeSchedule(BigDecimal rate, BigDecimal fixedAmount, BigDecimal payoutFixedAmount) {

  public static final FeeSchedule ZERO =
      new FeeSchedule(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

  public FeeSchedule {
    Objects.requireNonNull(rate, "rate must not be null");
    Objects.requireNonNull(fixedAmount, "fixedAmount must not be null");
    Objects.requireNonNull(payoutFixedAmount, "payoutFixedAmount must not be null");
    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
      throw new InvalidFeeScheduleException("rate must be in [0, 1): " + rate.toPlainString());
    }
    if (fixedAmount.signum() < 0) {
      throw new InvalidFeeScheduleException(
          "fixedAmount must not be negative: " + fixedAmount.toPlainString());
    }
    if (payoutFixedAmount.signum() < 0) {
      throw new InvalidFeeScheduleException(
          "payoutFixedAmount must not be negative: " + payoutFixedAmount.toPlainString());
    }
  }
}
