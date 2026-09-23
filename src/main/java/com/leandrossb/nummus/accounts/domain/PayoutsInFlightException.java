package com.leandrossb.nummus.accounts.domain;

import java.util.UUID;

/**
 * Thrown when freeze or close targets an account that still has a REQUESTED
 * hold — a payout or a refund. The hold's terminal legs — the return on
 * expiry/failure, or the fee at payout execution — post against the
 * merchant's ledger account, which the ledger rejects once it leaves ACTIVE,
 * so the transition would strand the held funds (permanently, for CLOSED).
 * The name predates refunds joining the guard; the semantic is any in-flight
 * hold.
 */
public class PayoutsInFlightException extends RuntimeException {

  public PayoutsInFlightException(UUID publicId) {
    super("payment account " + publicId + " still has a REQUESTED hold");
  }
}
