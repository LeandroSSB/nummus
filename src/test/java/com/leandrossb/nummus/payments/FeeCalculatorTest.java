package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.payments.application.FeeCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FeeCalculatorTest {

  private static Money brl(String amount) {
    return Money.ofBrl(amount);
  }

  @Test
  void roundsHalfUpToCentavos() {
    assertEquals(0, FeeCalculator.compute(brl("10.00"),
        new FeeSchedule(new BigDecimal("0.0099"), BigDecimal.ZERO)).fee()
        .compareTo(brl("0.10")));   // 0.0990 -> 0.10
    assertEquals(0, FeeCalculator.compute(brl("10.00"),
        new FeeSchedule(new BigDecimal("0.0094"), BigDecimal.ZERO)).fee()
        .compareTo(brl("0.09")));   // 0.0940 -> 0.09
    assertEquals(0, FeeCalculator.compute(brl("10.00"),
        new FeeSchedule(new BigDecimal("0.0095"), BigDecimal.ZERO)).fee()
        .compareTo(brl("0.10")));   // 0.0950 -> 0.10
  }

  @Test
  void composesPercentAndFixed() {
    var breakdown = FeeCalculator.compute(brl("100.00"),
        new FeeSchedule(new BigDecimal("0.0099"), new BigDecimal("0.39")));
    assertEquals(0, breakdown.fee().compareTo(brl("1.38")));    // 0.99 + 0.39
    assertEquals(0, breakdown.net().compareTo(brl("98.62")));
  }

  @Test
  void zeroScheduleYieldsZeroFee() {
    var breakdown = FeeCalculator.compute(brl("42.50"), FeeSchedule.ZERO);
    assertTrue(breakdown.fee().isZero());
    assertEquals(0, breakdown.net().compareTo(brl("42.50")));
  }

  @Test
  void feeIsCappedAtGross() {
    var breakdown = FeeCalculator.compute(brl("0.10"),
        new FeeSchedule(BigDecimal.ZERO, new BigDecimal("0.39")));
    assertEquals(0, breakdown.fee().compareTo(brl("0.10")));
    assertTrue(breakdown.net().isZero());
  }

  @Test
  void highScaleGrossRoundsOnce() {
    var breakdown = FeeCalculator.compute(brl("9.9999"),
        new FeeSchedule(new BigDecimal("0.0099"), BigDecimal.ZERO));
    assertEquals(0, breakdown.fee().compareTo(brl("0.10")));      // 0.09899901 -> 0.10
    assertEquals(0, breakdown.net().compareTo(brl("9.8999")));
  }
}
