package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/**
 * Thrown when a payout transition loses the status-guarded update race. Its
 * transaction — including the journal legs posted before the guard — rolled
 * back; re-reading the payout shows the winner's terminal state.
 */
public class ConcurrentPayoutException extends RuntimeException {

  public ConcurrentPayoutException(UUID publicId) {
    super("payout " + publicId + " transitioned concurrently; re-read the current state");
  }
}
