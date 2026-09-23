package com.leandrossb.nummus.psp_simulator.domain;

import com.leandrossb.nummus.payments.application.ChargeStatus;
import java.util.UUID;

/** Thrown when a payer action targets a refund that is no longer PENDING. */
public class RefundNotPendingException extends RuntimeException {

  public RefundNotPendingException(UUID publicId, ChargeStatus status) {
    super("refund " + publicId + " is not PENDING: " + status);
  }
}
