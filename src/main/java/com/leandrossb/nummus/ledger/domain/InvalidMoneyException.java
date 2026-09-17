package com.leandrossb.nummus.ledger.domain;

/** Thrown when a monetary amount violates the ledger's precision or sign rules. */
public class InvalidMoneyException extends RuntimeException {

  public InvalidMoneyException(String message) {
    super(message);
  }
}
