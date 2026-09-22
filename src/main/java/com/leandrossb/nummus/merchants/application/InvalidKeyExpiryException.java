package com.leandrossb.nummus.merchants.application;

/** A mint/rotation request carried an expiresIn that is not a positive
 *  ISO-8601 duration. */
public class InvalidKeyExpiryException extends RuntimeException {

  public InvalidKeyExpiryException() {
    super("expiresIn must be an ISO-8601 duration of at least one millisecond (e.g. P90D) or omitted");
  }
}
