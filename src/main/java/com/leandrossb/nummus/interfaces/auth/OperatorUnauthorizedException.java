package com.leandrossb.nummus.interfaces.auth;

/** Missing, invalid, or revoked credentials on an operator route. */
public class OperatorUnauthorizedException extends RuntimeException {

  public OperatorUnauthorizedException() {
    super("A valid API key is required");
  }
}
