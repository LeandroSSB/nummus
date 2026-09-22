package com.leandrossb.nummus.psp_simulator.domain;

import com.leandrossb.nummus.payments.application.ChargeStatus;
import java.util.UUID;

/** Thrown when a payer action targets a transfer that is no longer PENDING. */
public class TransferNotPendingException extends RuntimeException {

  public TransferNotPendingException(UUID publicId, ChargeStatus status) {
    super("transfer " + publicId + " is not PENDING: " + status);
  }
}
