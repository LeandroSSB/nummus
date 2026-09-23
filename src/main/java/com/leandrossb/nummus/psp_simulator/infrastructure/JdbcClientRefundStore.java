package com.leandrossb.nummus.psp_simulator.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.application.RefundStore;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedRefund;
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
public class JdbcClientRefundStore implements RefundStore {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final JdbcClient jdbc;

  public JdbcClientRefundStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public SimulatedRefund insert(SimulatedRefund refund) {
    jdbc.sql("""
        insert into psp_simulator.charge_refund
            (public_id, charge_public_id, amount, status, created_at, updated_at)
        values (:publicId, :chargePublicId, :amount, :status, :createdAt, :updatedAt)
        """)
        .param("publicId", refund.publicId())
        .param("chargePublicId", refund.chargePublicId())
        .param("amount", refund.amount().amount())
        .param("status", refund.status().name())
        .param("createdAt", toOffsetDateTime(refund.createdAt()))
        .param("updatedAt", toOffsetDateTime(refund.updatedAt()))
        .update();
    return refund;
  }

  @Override
  public Optional<SimulatedRefund> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, charge_public_id, amount, status, created_at, updated_at
        from psp_simulator.charge_refund where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapRefund(rs))
        .optional();
  }

  @Override
  public boolean transition(UUID publicId, ChargeStatus target) {
    int updated = jdbc.sql("""
        update psp_simulator.charge_refund set status = :status, updated_at = now()
        where public_id = :publicId and status = 'PENDING'
        """)
        .param("status", target.name())
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  @Override
  public Money totalRefunded(UUID chargePublicId) {
    return jdbc.sql("""
        select coalesce(sum(amount), 0) from psp_simulator.charge_refund
        where charge_public_id = :chargePublicId
          and status in ('PENDING', 'SUCCEEDED')
        """)
        .param("chargePublicId", chargePublicId)
        .query((rs, i) -> Money.of(rs.getBigDecimal(1), BRL))
        .single();
  }

  private SimulatedRefund mapRefund(ResultSet rs) throws SQLException {
    return new SimulatedRefund(
        rs.getObject("public_id", UUID.class),
        rs.getObject("charge_public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), BRL),
        ChargeStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
