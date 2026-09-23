package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/**
 * Thrown when a refund transition loses the status-guarded update race. Its
 * transaction — including the journal legs posted before the guard — rolled
 * back; re-reading the refund shows the winner's terminal state.
 */
public class ConcurrentRefundException extends RuntimeException {

  public ConcurrentRefundException(UUID publicId) {
    super("refund " + publicId + " transitioned concurrently; re-read the current state");
  }
}
