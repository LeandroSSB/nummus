package com.leandrossb.nummus.psp_simulator.domain;

import java.util.UUID;

/** Thrown when a transfer id does not exist at the simulated network. */
public class UnknownTransferException extends RuntimeException {

  public UnknownTransferException(UUID publicId) {
    super("unknown transfer: " + publicId);
  }
}
