package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * Thrown when a payout request exceeds the account's available balance. Carries
 * the derived balance the decision was made against and the requested total —
 * the payout amount plus the payout fee the request reserves room for — both
 * read under the account's row lock.
 */
public class InsufficientFundsException extends RuntimeException {

  private final UUID accountPublicId;
  private final Money available;
  private final Money requested;

  public InsufficientFundsException(UUID accountPublicId, Money available, Money requested) {
    super("insufficient funds on account " + accountPublicId + ": available "
        + available.amount().toPlainString() + " " + available.currency().getCurrencyCode()
        + ", requested " + requested.amount().toPlainString() + " "
        + requested.currency().getCurrencyCode());
    this.accountPublicId = accountPublicId;
    this.available = available;
    this.requested = requested;
  }

  public UUID accountPublicId() {
    return accountPublicId;
  }

  public Money available() {
    return available;
  }

  public Money requested() {
    return requested;
  }
}
