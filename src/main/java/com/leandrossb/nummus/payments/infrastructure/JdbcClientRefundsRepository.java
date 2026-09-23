package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.RefundsRepository;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.payments.domain.RefundStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientRefundsRepository implements RefundsRepository {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final JdbcClient jdbc;

  public JdbcClientRefundsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Refund insert(Refund refund) {
    jdbc.sql("""
        insert into payments.refund
          (public_id, intent_public_id, amount, status, network_refund_public_id,
           expires_at, created_at, hold_transaction_public_id)
        values (:publicId, :intentPublicId, :amount, :status, :networkRefundPublicId,
                :expiresAt, :createdAt, :holdTransactionPublicId)
        """)
        .param("publicId", refund.publicId())
        .param("intentPublicId", refund.intentPublicId())
        .param("amount", refund.amount().amount())
        .param("status", refund.status().name())
        .param("networkRefundPublicId", refund.networkRefundPublicId())
        .param("expiresAt", toOffsetDateTime(refund.expiresAt()))
        .param("createdAt", toOffsetDateTime(refund.createdAt()))
        .param("holdTransactionPublicId", refund.holdTransactionPublicId())
        .update();
    return refund;
  }

  @Override
  public Optional<Refund> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, intent_public_id, amount, status, network_refund_public_id,
               expires_at, created_at, settled_at, hold_transaction_public_id,
               execute_transaction_public_id, return_transaction_public_id
        from payments.refund where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapRefund(rs))
        .optional();
  }

  @Override
  public Money refundedTotal(UUID intentPublicId) {
    return jdbc.sql("""
        select coalesce(sum(amount), 0) from payments.refund
        where intent_public_id = :intentPublicId
          and status in ('REQUESTED', 'SETTLED')
        """)
        .param("intentPublicId", intentPublicId)
        .query((rs, i) -> Money.of(rs.getBigDecimal(1), BRL))
        .single();
  }

  private Refund mapRefund(ResultSet rs) throws SQLException {
    OffsetDateTime settledAt = rs.getObject("settled_at", OffsetDateTime.class);
    return new Refund(
        rs.getObject("public_id", UUID.class),
        rs.getObject("intent_public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL")),
        RefundStatus.valueOf(rs.getString("status")),
        rs.getObject("network_refund_public_id", UUID.class),
        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        settledAt == null ? null : settledAt.toInstant(),
        rs.getObject("hold_transaction_public_id", UUID.class),
        rs.getObject("execute_transaction_public_id", UUID.class),
        rs.getObject("return_transaction_public_id", UUID.class));
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
