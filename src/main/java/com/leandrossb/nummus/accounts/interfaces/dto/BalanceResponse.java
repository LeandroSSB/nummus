package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.accounts.application.BalanceComposition;
import java.math.BigDecimal;

/** REST view of a derived balance in natural sign, with the in-flight sums:
 *  pendingIncoming not yet booked, reservedOutgoing already reserved out. */
public record BalanceResponse(BigDecimal amount, BigDecimal pendingIncoming,
    BigDecimal reservedOutgoing, String currency) {

  public static BalanceResponse from(BalanceComposition composition) {
    return new BalanceResponse(composition.balance().amount(),
        composition.pendingIncoming().amount(), composition.reservedOutgoing().amount(),
        composition.balance().currency().getCurrencyCode());
  }
}
