package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * Money that belongs to the account's story but is not in its booked balance:
 * charges awaiting settlement (inbound — they have not touched the account's
 * ledger rows yet) and requested payouts/refunds (outbound — already reserved
 * out of the balance, not yet executed). Accounts owns this seam — not
 * payments — because balance and statement reads are accounts operations; the
 * payments module adapts to the port (the OutstandingHolds inversion).
 */
public interface MoneyInFlight {

  /** In-flight sums for one payment account, in natural (positive) sign. */
  Sums sums(UUID accountPublicId);

  /** pendingIncoming: gross amounts of CREATED intents. reservedOutgoing:
   *  amounts of REQUESTED payouts plus REQUESTED refunds — exactly the
   *  reservation legs' totals; the payout execution fee is not included. */
  record Sums(Money pendingIncoming, Money reservedOutgoing) {}
}
