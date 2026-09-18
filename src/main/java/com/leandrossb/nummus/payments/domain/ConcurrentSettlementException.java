package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/**
 * Thrown when a settle attempt loses the status-guarded transition race. Its transaction —
 * including the journal posting — rolled back; re-reading the intent shows SETTLED.
 */
public class ConcurrentSettlementException extends RuntimeException {

  public ConcurrentSettlementException(UUID publicId) {
    super("payment intent " + publicId + " was settled concurrently; re-read the current state");
  }
}
