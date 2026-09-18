package com.leandrossb.nummus.interfaces.idempotency;

/**
 * Thrown when an @Idempotent handler is reached without a usable
 * Idempotency-Key — the aspect's own fail-closed guard for requests that
 * bypass the web filter (e.g. encoded paths the filter's prefix rule misses).
 */
public class MissingIdempotencyKeyException extends RuntimeException {

  public MissingIdempotencyKeyException() {
    super("Idempotency-Key header (1-255 characters) is required on merchant writes");
  }
}
