package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/**
 * Thrown when a void targets an intent that is not CREATED. Carries the
 * state that made the void impossible — the intent's persisted status for
 * terminal rows, or the post-attempt network outcome when a racing pay/fail
 * won the charge.
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
