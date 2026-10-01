package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.accounts.application.MoneyInFlight;
import com.leandrossb.nummus.ledger.domain.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The payments module's adapter for the accounts-owned in-flight port. The
 * journal cannot answer "what is coming": an unpaid intent has no posting
 * anywhere, so lifecycle state — which payments owns — is the only source.
 * REQUESTED reservations, by contrast, are real postings; the consistency
 * guard test pins the two views together.
 */
@Component
public class PaymentsMoneyInFlight implements MoneyInFlight {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final JdbcClient jdbc;

  public PaymentsMoneyInFlight(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Sums sums(UUID accountPublicId) {
    var row = jdbc.sql("""
        select
          (select coalesce(sum(amount), 0) from payments.payment_intent
             where account_public_id = :accountPublicId and status = 'CREATED') as pending_incoming,
          (select coalesce(sum(amount), 0) from payments.payout
             where account_public_id = :accountPublicId and status = 'REQUESTED')
            + (select coalesce(sum(r.amount), 0) from payments.refund r
                 join payments.payment_intent i on i.public_id = r.intent_public_id
                 where i.account_public_id = :accountPublicId and r.status = 'REQUESTED')
            as reserved_outgoing
        """)
        .param("accountPublicId", accountPublicId)
        .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal("pending_incoming"),
            rs.getBigDecimal("reserved_outgoing")})
        .single();
    return new Sums(Money.of(row[0], BRL), Money.of(row[1], BRL));
  }
}
