package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/**
 * Thrown when a void targets an intent that is not CREATED. Carries the
 * status the guard saw — only a CREATED intent can be withdrawn; money
 * already paid settles instead, and a terminal intent is beyond withdrawal.
 */
public class IntentNotVoidableException extends RuntimeException {

  private final UUID publicId;
  private final IntentStatus current;

  public IntentNotVoidableException(UUID publicId, IntentStatus current) {
    super("intent not voidable: " + publicId + " is " + current);
    this.publicId = publicId;
    this.current = current;
  }

  public UUID publicId() {
    return publicId;
  }

  public IntentStatus current() {
    return current;
  }
}
