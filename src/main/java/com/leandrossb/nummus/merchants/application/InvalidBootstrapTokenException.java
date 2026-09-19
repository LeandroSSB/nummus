package com.leandrossb.nummus.merchants.application;

/** Raised when the presented token does not match the configured bootstrap token. */
public class InvalidBootstrapTokenException extends RuntimeException {

  public InvalidBootstrapTokenException() {
    super("invalid bootstrap token");
  }
}
