package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.ledger.domain.Money;
import java.math.BigDecimal;

/** REST view of a derived balance in natural sign. */
public record BalanceResponse(BigDecimal amount, String currency) {

  public static BalanceResponse from(Money balance) {
    return new BalanceResponse(balance.amount(), balance.currency().getCurrencyCode());
  }
}
