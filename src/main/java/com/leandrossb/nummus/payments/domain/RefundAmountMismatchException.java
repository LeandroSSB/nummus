package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** Thrown when the network echoes a refund amount different from the refund's — an invariant breach. */
public class RefundAmountMismatchException extends RuntimeException {

  public RefundAmountMismatchException(UUID refundPublicId, Money expected, Money actual) {
    super("refund " + refundPublicId + " amount mismatch: expected "
        + expected.amount().toPlainString() + " got " + actual.amount().toPlainString());
  }
}
