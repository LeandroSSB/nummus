package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.Payout;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the payments module's payouts. */
public interface PayoutsRepository {

  Payout insert(Payout payout);

  Optional<Payout> findByPublicId(UUID publicId);

  /** Settles a REQUESTED payout, stamping the execution link, the settled-at
   *  instant, and the fee fact. False when the row is no longer REQUESTED —
   *  the caller lost the race. */
  boolean markSettled(UUID publicId, UUID executeTransactionPublicId, Instant settledAt,
      Money feeAmount);

  /** Fails a REQUESTED payout, stamping the return link. False when the row is
   *  no longer REQUESTED — the caller lost the race. */
  boolean markFailed(UUID publicId, UUID returnTransactionPublicId);

  /** Expires a REQUESTED payout, stamping the return link. False when the row
   *  is no longer REQUESTED — the caller lost the race. */
  boolean markExpired(UUID publicId, UUID returnTransactionPublicId);

  /** SETTLED rows with settled_at in [from, to), ordered by settled_at then id. */
  List<Payout> findSettledBetween(Instant from, Instant to);
}
