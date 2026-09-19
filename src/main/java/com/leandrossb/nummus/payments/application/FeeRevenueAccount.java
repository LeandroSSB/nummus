package com.leandrossb.nummus.payments.application;

import java.util.UUID;

/**
 * The system revenue account the V12 migration seeds — settlement fees credited
 * here. Seeded by migration (the composition layer) with a fixed public id;
 * runtime code never writes to the ledger schema directly.
 */
public final class FeeRevenueAccount {

  public static final UUID PUBLIC_ID = UUID.fromString("5f9c3b2e-0000-4000-8000-000000000002");
  public static final String NAME = "payment fees";

  private FeeRevenueAccount() {
  }
}
