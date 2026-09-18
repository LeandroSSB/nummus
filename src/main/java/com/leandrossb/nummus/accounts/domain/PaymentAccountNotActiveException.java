package com.leandrossb.nummus.accounts.domain;

import java.util.UUID;

/** Thrown when an operation requires a non-terminal payment account but the account is CLOSED. */
public class PaymentAccountNotActiveException extends RuntimeException {

  public PaymentAccountNotActiveException(UUID publicId, AccountStatus status) {
    super("payment account " + publicId + " is CLOSED: " + status);
  }
}
