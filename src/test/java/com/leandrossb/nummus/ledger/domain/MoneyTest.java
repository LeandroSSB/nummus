package com.leandrossb.nummus.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

  private static final Currency BRL = Currency.getInstance("BRL");
  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void acceptsAmountsWithScaleUpToFour() {
    Money.of(new BigDecimal("150"), BRL);
    Money.of(new BigDecimal("150.00"), BRL);
    Money.of(new BigDecimal("150.0000"), BRL);
    Money.ofBrl("0.0001");
  }

  @Test
  void rejectsScaleBeyondFour() {
    var ex = assertThrows(InvalidMoneyException.class, () -> Money.ofBrl("1.23456"));
    assertTrue(ex.getMessage().contains("scale"));
  }

  @Test
  void rejectsNullAmountOrCurrency() {
    assertThrows(NullPointerException.class, () -> Money.of(null, BRL));
    assertThrows(NullPointerException.class, () -> Money.of(BigDecimal.TEN, null));
  }

  @Test
  void negativeAmountsAreAllowedBecauseBalancesCanBeNegative() {
    assertTrue(Money.ofBrl("-3.5000").isNegative());
    assertTrue(Money.ofBrl("-3.5000").subtract(Money.ofBrl("1.0000")).isNegative());
  }

  @Test
  void compareToIgnoresScaleDifferences() {
    assertEquals(0, Money.ofBrl("2.1").compareTo(Money.ofBrl("2.10")));
    assertEquals(0, Money.ofBrl("2.10").compareTo(Money.ofBrl("2.1")));
    assertTrue(Money.ofBrl("2.10").compareTo(Money.ofBrl("2.11")) < 0);
  }

  @Test
  void arithmeticChecksCurrency() {
    assertThrows(CurrencyMismatchException.class, () -> Money.ofBrl("1.00").add(Money.of(BigDecimal.ONE, USD)));
    assertThrows(CurrencyMismatchException.class, () -> Money.ofBrl("1.00").subtract(Money.of(BigDecimal.ONE, USD)));
    assertEquals(Money.ofBrl("3.0000").compareTo(Money.ofBrl("1.1000").add(Money.ofBrl("1.9000"))), 0);
  }

  @Test
  void zeroHelpersWork() {
    assertTrue(Money.ofBrl("0.0000").isZero());
    assertTrue(Money.ofBrl("5.0000").isPositive());
  }
}
