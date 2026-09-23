package com.leandrossb.nummus.psp_simulator.domain;

import java.util.UUID;

/** Thrown when a refund id does not exist at the simulated network. */
public class UnknownRefundException extends RuntimeException {

  public UnknownRefundException(UUID publicId) {
    super("unknown refund: " + publicId);
  }
}
