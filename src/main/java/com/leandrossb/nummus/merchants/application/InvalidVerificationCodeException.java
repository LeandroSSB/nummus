package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** Raised when the presented verification code does not match the stored hash. */
public final class InvalidVerificationCodeException extends RuntimeException {

  public InvalidVerificationCodeException(UUID publicId) {
    super("verification code rejected: " + publicId);
  }
}
