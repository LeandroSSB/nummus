package com.leandrossb.nummus.merchants.application;

/** Raised when an ACTIVE operator key already exists — the one-time bootstrap is consumed. */
public class BootstrapAlreadyUsedException extends RuntimeException {

  public BootstrapAlreadyUsedException() {
    super("operator bootstrap already used");
  }
}
