package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** Raised for a bank account that does not exist or is not the caller's. */
public final class UnknownBankAccountException extends RuntimeException {

  public UnknownBankAccountException(UUID publicId) {
    super("bank account not found: " + publicId);
  }
}
