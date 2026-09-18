package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/** Thrown when a payment intent id does not exist. */
public class UnknownPaymentIntentException extends RuntimeException {

  public UnknownPaymentIntentException(UUID publicId) {
    super("unknown payment intent: " + publicId);
  }
}
