package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** Raised for a merchant id that does not exist. */
public class UnknownMerchantException extends RuntimeException {

  public UnknownMerchantException(UUID publicId) {
    super("merchant not found: " + publicId);
  }
}
