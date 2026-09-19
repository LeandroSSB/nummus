package com.leandrossb.nummus.conciliation.application;

import java.util.UUID;

/** Raised for unknown report ids. */
public class UnknownConciliationReportException extends RuntimeException {

  public UnknownConciliationReportException(UUID publicId) {
    super("conciliation report not found: " + publicId);
  }
}
