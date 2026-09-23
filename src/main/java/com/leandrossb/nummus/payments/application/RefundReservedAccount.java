package com.leandrossb.nummus.payments.application;

import java.util.UUID;

/**
 * The pooled refund-reserve liability the V19 migration seeds — funds held
 * aside for requested refunds. The two-phase hold posts through it: the
 * request credits it (holding the merchant's funds aside), execute debits it
 * as the money returns to the network, and a return releases the hold back to
 * the merchant. Seeded by migration (the composition layer) with a fixed
 * public id; runtime code never writes to the ledger schema directly.
 */
public final class RefundReservedAccount {

  public static final UUID PUBLIC_ID = UUID.fromString("5f9c3b2e-0000-4000-8000-000000000004");

  private RefundReservedAccount() {
  }
}
