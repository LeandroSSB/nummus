package com.leandrossb.nummus.accounts.application;

import java.util.UUID;

/**
 * Whether the payment account still has a hold awaiting its terminal state.
 * Accounts owns this seam — not payments — because freeze and close are the
 * operations that would strand a REQUESTED hold: its return and fee legs post
 * against the merchant's ledger account, which the ledger rejects once the
 * account leaves ACTIVE (and CLOSED is terminal). Payout requests and refund
 * requests both hold funds aside — one seam covers both. The payments module
 * adapts to the port (the M3 inversion); accounts never depends on payments.
 */
public interface OutstandingHolds {

  /** True while any payout or refund of the account is still REQUESTED. */
  boolean anyPending(UUID accountPublicId);
}
