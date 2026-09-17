package com.leandrossb.nummus.ledger.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Monetary amount with explicit currency. Amounts are never {@code double}/{@code float};
 * comparisons use {@link BigDecimal#compareTo} so {@code 2.1} and {@code 2.10} are equal.
 * Negative amounts are valid (derived balances swing both ways); posting-level positivity
 * is enforced by {@link PostingDraft}.
 */
public record Money(BigDecimal amount, Currency currency) {

  private static final int MAX_SCALE = 4;

  public Money {
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    if (amount.scale() > MAX_SCALE) {
      throw new InvalidMoneyException(
          "amount scale must be at most " + MAX_SCALE + ": " + amount.toPlainString());
    }
  }

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  public static Money ofBrl(String amount) {
    return of(new BigDecimal(amount), Currency.getInstance("BRL"));
  }

  public Money add(Money other) {
    assertSameCurrency(other);
    return of(amount.add(other.amount), currency);
  }

  public Money subtract(Money other) {
    assertSameCurrency(other);
    return of(amount.subtract(other.amount), currency);
  }

  public int compareTo(Money other) {
    assertSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  private void assertSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new CurrencyMismatchException(
          "currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
    }
  }
}
