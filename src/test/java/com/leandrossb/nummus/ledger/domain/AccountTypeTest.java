package com.leandrossb.nummus.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AccountTypeTest {

  @Test
  void assetAndExpenseHaveDebitNormalBalance() {
    assertEquals(Direction.DEBIT, AccountType.ASSET.normalBalance());
    assertEquals(Direction.DEBIT, AccountType.EXPENSE.normalBalance());
  }

  @Test
  void liabilityEquityRevenueHaveCreditNormalBalance() {
    assertEquals(Direction.CREDIT, AccountType.LIABILITY.normalBalance());
    assertEquals(Direction.CREDIT, AccountType.EQUITY.normalBalance());
    assertEquals(Direction.CREDIT, AccountType.REVENUE.normalBalance());
  }
}
