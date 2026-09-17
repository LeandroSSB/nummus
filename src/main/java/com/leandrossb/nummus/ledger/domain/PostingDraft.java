package com.leandrossb.nummus.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/** A posting to be written as part of a new journal transaction. Amount must be strictly positive. */
public record PostingDraft(UUID accountPublicId, Direction direction, Money amount) {

  public PostingDraft {
    Objects.requireNonNull(accountPublicId, "accountPublicId must not be null");
    Objects.requireNonNull(direction, "direction must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    if (!amount.isPositive()) {
      throw new InvalidMoneyException(
          "posting amount must be strictly positive: " + amount.amount().toPlainString());
    }
  }
}
