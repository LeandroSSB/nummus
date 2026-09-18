package com.leandrossb.nummus.psp_simulator.domain;

import com.leandrossb.nummus.payments.application.ChargeStatus;
import java.util.UUID;

/** Thrown when a payer action targets a charge that is no longer PENDING. */
public class ChargeNotPendingException extends RuntimeException {

  public ChargeNotPendingException(UUID publicId, ChargeStatus status) {
    super("charge " + publicId + " is not PENDING: " + status);
  }
}
