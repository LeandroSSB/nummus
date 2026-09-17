package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** Thrown when a transaction id does not exist. */
public class UnknownTransactionException extends RuntimeException {

  public UnknownTransactionException(UUID publicId) {
    super("unknown transaction: " + publicId);
  }
}
