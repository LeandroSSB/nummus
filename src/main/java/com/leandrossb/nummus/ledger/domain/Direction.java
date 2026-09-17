package com.leandrossb.nummus.ledger.domain;

/** Posting side in the double-entry journal. */
public enum Direction {
  DEBIT,
  CREDIT;

  public Direction opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }
}
