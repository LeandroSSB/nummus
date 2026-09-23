package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/**
 * Thrown when a refund targets an intent that is not SETTLED. Carries the
 * status the guard saw — only settled money exists to return; CREATED never
 * arrived, and FAILED/EXPIRED charges were never collectable.
 */
public class IntentNotRefundableException extends RuntimeException {

  private final UUID intentPublicId;
  private final IntentStatus status;

  public IntentNotRefundableException(UUID intentPublicId, IntentStatus status) {
    super("payment intent " + intentPublicId + " is not refundable: " + status);
    this.intentPublicId = intentPublicId;
    this.status = status;
  }

  public UUID intentPublicId() {
    return intentPublicId;
  }

  public IntentStatus status() {
    return status;
  }
}
