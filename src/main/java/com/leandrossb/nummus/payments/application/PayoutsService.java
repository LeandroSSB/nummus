package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.Payout;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The payout (money-out) side of the payments module. A request reserves the
 * funds in the journal under the account's row lock before the payout row
 * exists; execute and return settle or release that reservation.
 */
public interface PayoutsService {

  Payout create(UUID merchantPublicId, CreatePayoutCommand cmd);

  /** Reads the payout, lazily driving its terminal transition: expiry returns
   *  the reservation, a failed transfer returns it, a succeeded one executes
   *  it out of the reserve (charging the settle-time payout fee). Terminal
   *  states are returned as-is without polling the network. */
  Payout get(UUID merchantPublicId, UUID publicId);

  /** Settled payouts in [from, to) — conciliation's view of internal money-out
   *  settlements, keyed by the network transfer each payout executed. */
  List<MoneyOutSettlementView> listSettlements(Instant from, Instant to);
}
