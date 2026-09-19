package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Pure fee math: the composed fee (gross x rate + fixed) is rounded HALF_UP to
 * centavos exactly once and capped at the gross amount, so the net is never
 * negative. Stateless and side-effect free.
 */
public final class FeeCalculator {

  private FeeCalculator() {
  }

  public static FeeBreakdown compute(Money gross, FeeSchedule fee) {
    Objects.requireNonNull(gross, "gross must not be null");
    Objects.requireNonNull(fee, "fee must not be null");
    BigDecimal raw = gross.amount().multiply(fee.rate()).add(fee.fixedAmount());
    BigDecimal charged = raw.setScale(2, RoundingMode.HALF_UP)
        .max(BigDecimal.ZERO)
        .min(gross.amount());
    Money feeMoney = Money.of(charged, gross.currency());
    return new FeeBreakdown(feeMoney, gross.subtract(feeMoney));
  }
}
