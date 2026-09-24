package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** Raised when a transition no longer applies in the account's current status. */
public final class BankAccountNotVerifiableException extends RuntimeException {

  public BankAccountNotVerifiableException(UUID publicId, String status) {
    super("bank account is not pending verification: " + publicId + " (status: " + status + ")");
  }
}
