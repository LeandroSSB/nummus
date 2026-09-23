package com.leandrossb.nummus.payments.application;

import java.util.Set;

/** Single source of the refund lifecycle event type strings (webhook catalog mirrors this). */
public final class RefundEventTypes {

  public static final String SETTLED = "refund.settled";
  public static final String FAILED = "refund.failed";
  public static final String EXPIRED = "refund.expired";
  public static final Set<String> ALL = Set.of(SETTLED, FAILED, EXPIRED);

  private RefundEventTypes() {
  }
}
