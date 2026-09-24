package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** Raised when a payout destination is not VERIFIED. */
public final class BankAccountNotVerifiedException extends RuntimeException {

  public BankAccountNotVerifiedException(UUID publicId, String status) {
    super("bank account is not verified: " + publicId + " (status: " + status + ")");
  }
}
