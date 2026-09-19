package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** Raised for a key that does not exist or is not the caller's. */
public class UnknownApiKeyException extends RuntimeException {

  public UnknownApiKeyException(UUID publicId) {
    super("api key not found: " + publicId);
  }
}
