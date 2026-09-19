package com.leandrossb.nummus.interfaces.auth;

/** Missing, invalid, or revoked credentials on a merchant route. */
public class MerchantUnauthorizedException extends RuntimeException {

  public MerchantUnauthorizedException() {
    super("A valid API key is required");
  }
}
