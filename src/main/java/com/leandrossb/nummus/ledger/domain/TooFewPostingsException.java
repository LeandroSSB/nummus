package com.leandrossb.nummus.ledger.domain;

/** Thrown when a transaction has fewer than two postings or misses one side of the entry. */
public class TooFewPostingsException extends RuntimeException {

  public TooFewPostingsException(int postingCount) {
    super("a transaction needs at least one DEBIT and one CREDIT posting, got " + postingCount);
  }
}
