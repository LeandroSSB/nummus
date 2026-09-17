package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** Thrown when an account id does not exist. */
public class UnknownAccountException extends RuntimeException {

  public UnknownAccountException(UUID publicId) {
    super("unknown account: " + publicId);
  }
}
