package com.leandrossb.nummus.interfaces.auth;

/** A role mismatch on an operator route: the caller holds a merchant key. */
public class OperatorKeyRequiredException extends RuntimeException {

  public OperatorKeyRequiredException() {
    super("An operator API key is required");
  }
}
