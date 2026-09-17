package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** Thrown when reversing a transaction that has already been reversed. */
public class TransactionAlreadyReversedException extends RuntimeException {

  public TransactionAlreadyReversedException(UUID publicId, Throwable cause) {
    super("transaction " + publicId + " has already been reversed", cause);
  }
}
