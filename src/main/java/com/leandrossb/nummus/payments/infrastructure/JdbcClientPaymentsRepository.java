package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsRepository;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientPaymentsRepository implements PaymentsRepository {

  private final JdbcClient jdbc;

  public JdbcClientPaymentsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PaymentIntent insert(PaymentIntent intent) {
    jdbc.sql("""
        insert into payments.payment_intent
          (public_id, account_public_id, amount, status, charge_public_id, expires_at, created_at)
        values (:publicId, :accountPublicId, :amount, :status, :chargePublicId, :expiresAt, :createdAt)
        """)
        .param("publicId", intent.publicId())
        .param("accountPublicId", intent.accountPublicId())
        .param("amount", intent.amount().amount())
        .param("status", intent.status().name())
        .param("chargePublicId", intent.chargePublicId())
        .param("expiresAt", toOffsetDateTime(intent.expiresAt()))
        .param("createdAt", toOffsetDateTime(intent.createdAt()))
        .update();
    return intent;
  }

  @Override
  public Optional<PaymentIntent> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, account_public_id, amount, status, charge_public_id,
               expires_at, created_at, settled_at, journal_transaction_public_id, fee_amount
        from payments.payment_intent where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapIntent(rs))
        .optional();
  }

  @Override
  public List<PaymentIntent> findSettledBetween(Instant from, Instant to) {
    return jdbc.sql("""
        select public_id, account_public_id, amount, status, charge_public_id,
               expires_at, created_at, settled_at, journal_transaction_public_id, fee_amount
        from payments.payment_intent
        where status = 'SETTLED' and settled_at >= :from and settled_at < :to
        order by settled_at, id
        """)
        .param("from", toOffsetDateTime(from))
        .param("to", toOffsetDateTime(to))
        .query((rs, i) -> mapIntent(rs))
        .list();
  }

  @Override
  public boolean transitionToExpired(UUID publicId) {
    return guardedTransition(publicId, "EXPIRED", null, null);
  }

  @Override
  public boolean transitionToFailed(UUID publicId) {
    return guardedTransition(publicId, "FAILED", null, null);
  }

  @Override
  public boolean markSettled(UUID publicId, UUID journalTransactionPublicId, Instant settledAt,
      Money feeAmount) {
    int updated = jdbc.sql("""
        update payments.payment_intent
        set status = 'SETTLED', settled_at = :settledAt, journal_transaction_public_id = :journalTx,
            fee_amount = :fee
        where public_id = :publicId and status = 'CREATED'
        """)
        .param("settledAt", toOffsetDateTime(settledAt))
        .param("journalTx", journalTransactionPublicId)
        .param("fee", feeAmount == null ? null : feeAmount.amount())
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  private boolean guardedTransition(UUID publicId, String target, UUID journalTx, Instant at) {
    int updated = jdbc.sql("""
        update payments.payment_intent set status = :status
        where public_id = :publicId and status = 'CREATED'
        """)
        .param("status", target)
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  private PaymentIntent mapIntent(ResultSet rs) throws SQLException {
    OffsetDateTime settledAt = rs.getObject("settled_at", OffsetDateTime.class);
    BigDecimal feeAmount = rs.getBigDecimal("fee_amount");
    return new PaymentIntent(
        rs.getObject("public_id", UUID.class),
        rs.getObject("account_public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL")),
        IntentStatus.valueOf(rs.getString("status")),
        rs.getObject("charge_public_id", UUID.class),
        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        settledAt == null ? null : settledAt.toInstant(),
        rs.getObject("journal_transaction_public_id", UUID.class),
        feeAmount == null ? null : Money.of(feeAmount, Currency.getInstance("BRL")));
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
