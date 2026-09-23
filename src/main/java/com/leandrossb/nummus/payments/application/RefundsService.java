package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.Refund;
import java.util.UUID;

/**
 * The refund (money-back) side of the payments module. A request holds the
 * funds in the journal under the intent's account row lock — the same fence
 * payout requests take — before the refund row exists; execute and return
 * settle or release that hold.
 */
public interface RefundsService {

  /** Requests a refund of a settled intent, holding the exact amount aside.
   *  The intent must be SETTLED and its account ACTIVE; the request must fit
   *  both the intent's refundable remainder and the account's available
   *  balance. */
  Refund create(UUID merchantPublicId, UUID intentPublicId, CreateRefundCommand cmd);

  /** Reads a refund, driving its lazy lifecycle: a REQUESTED refund whose
   *  network side moved settles or returns its hold; expiry returns the hold
   *  without consulting the network. Terminal rows are returned untouched. */
  Refund get(UUID merchantPublicId, UUID publicId);

  /** Sum of the intent's holding (REQUESTED) and executed (SETTLED) refunds —
   *  the term every further refund request is capped against. FAILED and
   *  EXPIRED refunds released their share and drop out; an intent without
   *  refunds sums to zero. */
  Money refundedTotal(UUID intentPublicId);
}
