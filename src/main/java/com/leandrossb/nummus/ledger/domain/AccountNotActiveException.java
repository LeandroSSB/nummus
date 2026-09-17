package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** Thrown when an operation requires an ACTIVE account but the account is FROZEN or CLOSED. */
public class AccountNotActiveException extends RuntimeException {

  public AccountNotActiveException(UUID publicId, AccountStatus status) {
    super("account " + publicId + " is not ACTIVE: " + status);
  }
}
