package com.leandrossb.nummus.psp_simulator.domain;

import java.util.UUID;

/** Thrown when a charge id does not exist at the simulated network. */
public class UnknownChargeException extends RuntimeException {

  public UnknownChargeException(UUID publicId) {
    super("unknown charge: " + publicId);
  }
}
