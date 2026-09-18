package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** Thrown when the network echoes a charge amount different from the intent's — an invariant breach. */
public class ChargeAmountMismatchException extends RuntimeException {

  public ChargeAmountMismatchException(UUID chargePublicId, Money expected, Money actual) {
    super("charge " + chargePublicId + " amount mismatch: expected "
        + expected.amount().toPlainString() + " got " + actual.amount().toPlainString());
  }
}
