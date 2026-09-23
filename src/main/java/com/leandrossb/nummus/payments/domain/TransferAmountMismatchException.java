package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** Thrown when the network echoes a transfer amount different from the payout's — an invariant breach. */
public class TransferAmountMismatchException extends RuntimeException {

  public TransferAmountMismatchException(UUID transferPublicId, Money expected, Money actual) {
    super("transfer " + transferPublicId + " amount mismatch: expected "
        + expected.amount().toPlainString() + " got " + actual.amount().toPlainString());
  }
}
