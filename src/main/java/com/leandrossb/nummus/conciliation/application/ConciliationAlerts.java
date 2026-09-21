package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;
import java.util.UUID;

/** Push-side port for conciliation alerts. Implemented by the webhook outbox;
 *  called inside the ingest transaction, so an alert commits with the report
 *  or not at all. */
public interface ConciliationAlerts {

  /** Fired exactly once per report that lands OPEN. */
  void reportOpen(UUID reportPublicId, Instant from, Instant to, int matched,
      int amountMismatched, int missingInternal, int missingExternal);
}
