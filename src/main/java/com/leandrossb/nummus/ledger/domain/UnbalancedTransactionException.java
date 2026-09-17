package com.leandrossb.nummus.ledger.domain;

/** Thrown when total debits and total credits of a transaction differ. */
public class UnbalancedTransactionException extends RuntimeException {

  public UnbalancedTransactionException(Money debits, Money credits) {
    super("transaction is not balanced: debits=" + debits.amount().toPlainString()
        + " credits=" + credits.amount().toPlainString());
  }
}
