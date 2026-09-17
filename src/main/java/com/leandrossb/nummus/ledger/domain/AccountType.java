package com.leandrossb.nummus.ledger.domain;

/**
 * Classic chart-of-accounts classification. Each type carries the side on which
 * the account's balance grows naturally; the database stores only this enum.
 */
public enum AccountType {
  ASSET,
  LIABILITY,
  EQUITY,
  REVENUE,
  EXPENSE;

  public Direction normalBalance() {
    return (this == ASSET || this == EXPENSE) ? Direction.DEBIT : Direction.CREDIT;
  }
}
