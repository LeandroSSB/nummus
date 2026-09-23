package com.leandrossb.nummus.payments.application;

import java.util.UUID;

/**
 * The pooled payout-reserve liability the V18 migration seeds — funds held aside
 * for requested payouts. The two-phase reservation posts through it: the request
 * credits it (holding the merchant's funds), execute debits it as the money
 * leaves, and a return releases the reservation back to the merchant. Seeded by
 * migration (the composition layer) with a fixed public id; runtime code never
 * writes to the ledger schema directly.
 */
public final class PayoutReservedAccount {

  public static final UUID PUBLIC_ID = UUID.fromString("5f9c3b2e-0000-4000-8000-000000000003");

  private PayoutReservedAccount() {
  }
}
