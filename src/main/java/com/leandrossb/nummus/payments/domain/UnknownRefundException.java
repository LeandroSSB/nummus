package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/** Thrown when a refund id does not exist. */
public class UnknownRefundException extends RuntimeException {

  public UnknownRefundException(UUID publicId) {
    super("unknown refund: " + publicId);
  }
}
