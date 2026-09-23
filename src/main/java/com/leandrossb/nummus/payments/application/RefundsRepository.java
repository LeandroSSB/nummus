package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.Refund;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the payments module's refunds. */
public interface RefundsRepository {

  Refund insert(Refund refund);

  Optional<Refund> findByPublicId(UUID publicId);

  /** Sum of the holding (REQUESTED) and executed (SETTLED) refunds of the
   *  intent — what the refundable remainder derives from. */
  Money refundedTotal(UUID intentPublicId);

  /** Settles a REQUESTED refund, stamping the execution link and the
   *  settled-at instant. False when the row is no longer REQUESTED — the
   *  caller lost the race. */
  boolean markSettled(UUID publicId, UUID executeTransactionPublicId, Instant settledAt);

  /** Fails a REQUESTED refund, stamping the return link. False when the row is
   *  no longer REQUESTED — the caller lost the race. */
  boolean markFailed(UUID publicId, UUID returnTransactionPublicId);

  /** Expires a REQUESTED refund, stamping the return link. False when the row
   *  is no longer REQUESTED — the caller lost the race. */
  boolean markExpired(UUID publicId, UUID returnTransactionPublicId);
}
