package com.leandrossb.nummus.conciliation.application;

import java.util.Set;

/** Single source of the conciliation event type strings (the operator
 *  webhook catalog mirrors this). */
public final class ConciliationEventTypes {

  public static final String REPORT_OPEN = "conciliation.report_open";
  public static final Set<String> ALL = Set.of(REPORT_OPEN);

  private ConciliationEventTypes() {
  }
}
