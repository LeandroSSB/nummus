package com.leandrossb.nummus.conciliation.application;

import java.util.UUID;

/** The network emitted the same charge twice in one report — rejected at ingest. */
public class DuplicateSettlementLinesException extends RuntimeException {

  public DuplicateSettlementLinesException(UUID chargePublicId) {
    super("duplicate settlement line for charge " + chargePublicId);
  }
}
