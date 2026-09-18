package com.leandrossb.nummus.interfaces.idempotency;

/** An Idempotency-Key was reused for a different request. Keys identify one logical operation. */
public class IdempotencyKeyReuseException extends RuntimeException {

  public IdempotencyKeyReuseException(String key) {
    super("Idempotency-Key '" + key + "' was already used with a different request");
  }
}
