package com.leandrossb.nummus.merchants.application;

/** Raised when no bootstrap token is configured — the endpoint does not exist. */
public class BootstrapUnavailableException extends RuntimeException {

  public BootstrapUnavailableException() {
    super("operator bootstrap is not configured");
  }
}
