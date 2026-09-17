package com.leandrossb.nummus.ledger.domain;

/** Thrown when amounts in different currencies are combined, or a non-BRL amount reaches the BRL ledger. */
public class CurrencyMismatchException extends RuntimeException {

  public CurrencyMismatchException(String message) {
    super(message);
  }
}
