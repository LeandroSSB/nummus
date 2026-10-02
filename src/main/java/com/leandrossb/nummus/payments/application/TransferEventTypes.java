package com.leandrossb.nummus.payments.application;

import java.util.Set;

/** Single source of the transfer event type strings (webhook catalog mirrors this). */
public final class TransferEventTypes {

  public static final String COMPLETED = "transfer.completed";
  public static final Set<String> ALL = Set.of(COMPLETED);

  private TransferEventTypes() {
  }
}
