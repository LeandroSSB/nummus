package com.leandrossb.nummus.interfaces.auth;

/** A role mismatch on a merchant route: the caller holds an operator key. */
public class MerchantKeyRequiredException extends RuntimeException {

  public MerchantKeyRequiredException() {
    super("A merchant API key is required");
  }
}
