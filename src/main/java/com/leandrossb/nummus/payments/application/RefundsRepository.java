package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.Refund;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the payments module's refunds. */
public interface RefundsRepository {

  Refund insert(Refund refund);

  Optional<Refund> findByPublicId(UUID publicId);

  /** Sum of the holding (REQUESTED) and executed (SETTLED) refunds of the
   *  intent — what the refundable remainder derives from. */
  Money refundedTotal(UUID intentPublicId);
}
