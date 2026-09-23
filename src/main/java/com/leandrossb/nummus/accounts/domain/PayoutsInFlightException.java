package com.leandrossb.nummus.accounts.domain;

import java.util.UUID;

/**
 * Thrown when freeze or close targets an account that still has a REQUESTED
 * payout. The payout's terminal legs — the return on expiry/failure or the
 * fee at execution — post against the merchant's ledger account, which the
 * ledger rejects once it leaves ACTIVE, so the transition would strand the
 * reservation (permanently, for CLOSED).
 */
public class PayoutsInFlightException extends RuntimeException {

  public PayoutsInFlightException(UUID publicId) {
    super("payment account " + publicId + " still has a REQUESTED payout");
  }
}
