package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.accounts.application.OutstandingHolds;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The payments module's adapter for the accounts-owned in-flight port (the M3
 * inversion): a hold is in flight exactly while its row reads REQUESTED — a
 * requested payout on the account, or a requested refund of one of the
 * account's intents. Joins the caller's transaction — the guard must see the
 * same commit frontier as the account transition it gates.
 */
@Component
public class PaymentsOutstandingHolds implements OutstandingHolds {

  private final JdbcClient jdbc;

  public PaymentsOutstandingHolds(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean anyPending(UUID accountPublicId) {
    return jdbc.sql("""
        select exists(
          select 1 from payments.payout
            where account_public_id = :accountPublicId and status = 'REQUESTED'
          union
          select 1 from payments.refund r
            join payments.payment_intent i on i.public_id = r.intent_public_id
            where i.account_public_id = :accountPublicId and r.status = 'REQUESTED')
        """)
        .param("accountPublicId", accountPublicId)
        .query(Boolean.class)
        .single();
  }
}
