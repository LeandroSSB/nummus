package com.leandrossb.nummus.payments.application;

import java.util.UUID;

/**
 * The system clearing asset the V5 migration seeds — money the payment network owes us.
 * Seeded by migration (the composition layer) with a fixed public id; runtime code never
 * writes to the ledger schema directly.
 */
public final class PaymentClearingAccount {

  public static final UUID PUBLIC_ID = UUID.fromString("5f9c3b2e-0000-4000-8000-000000000001");

  private PaymentClearingAccount() {
  }
}
