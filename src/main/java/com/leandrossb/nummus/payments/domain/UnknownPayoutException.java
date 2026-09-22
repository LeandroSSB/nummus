package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/** Thrown when a payout id does not exist. */
public class UnknownPayoutException extends RuntimeException {

  public UnknownPayoutException(UUID publicId) {
    super("unknown payout: " + publicId);
  }
}
