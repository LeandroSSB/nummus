package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.accounts.application.OutstandingPayouts;
import com.leandrossb.nummus.payments.application.PayoutsRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The payments module's adapter for the accounts-owned in-flight port (the M3
 * inversion): a payout is in flight exactly while its row reads REQUESTED.
 * Joins the caller's transaction — the guard must see the same commit
 * frontier as the account transition it gates.
 */
@Component
public class PaymentsOutstandingPayouts implements OutstandingPayouts {

  private final PayoutsRepository payouts;

  public PaymentsOutstandingPayouts(PayoutsRepository payouts) {
    this.payouts = payouts;
  }

  @Override
  public boolean anyRequested(UUID accountPublicId) {
    return payouts.existsRequestedByAccount(accountPublicId);
  }
}
