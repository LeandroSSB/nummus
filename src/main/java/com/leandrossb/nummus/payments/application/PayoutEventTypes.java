package com.leandrossb.nummus.payments.application;

import java.util.Set;

/** Single source of the payout lifecycle event type strings (webhook catalog mirrors this). */
public final class PayoutEventTypes {

  public static final String SETTLED = "payout.settled";
  public static final String FAILED = "payout.failed";
  public static final String EXPIRED = "payout.expired";
  public static final Set<String> ALL = Set.of(SETTLED, FAILED, EXPIRED);

  private PayoutEventTypes() {
  }
}
