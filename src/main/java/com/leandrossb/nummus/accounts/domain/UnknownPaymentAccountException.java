package com.leandrossb.nummus.accounts.domain;

import java.util.UUID;

/** Thrown when a payment account id does not exist. */
public class UnknownPaymentAccountException extends RuntimeException {

  public UnknownPaymentAccountException(UUID publicId) {
    super("unknown payment account: " + publicId);
  }
}
