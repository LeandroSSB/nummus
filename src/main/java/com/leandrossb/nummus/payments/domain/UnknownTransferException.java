package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/** Thrown when a transfer id does not exist. */
public class UnknownTransferException extends RuntimeException {

  public UnknownTransferException(UUID publicId) {
    super("unknown transfer: " + publicId);
  }
}
