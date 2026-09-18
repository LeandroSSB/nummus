package com.leandrossb.nummus.payments.application;

import java.util.Set;

/** Single source of the intent lifecycle event type strings (webhook catalog mirrors this). */
public final class IntentEventTypes {

  public static final String SETTLED = "payment_intent.settled";
  public static final String FAILED = "payment_intent.failed";
  public static final String EXPIRED = "payment_intent.expired";
  public static final Set<String> ALL = Set.of(SETTLED, FAILED, EXPIRED);

  private IntentEventTypes() {
  }
}
