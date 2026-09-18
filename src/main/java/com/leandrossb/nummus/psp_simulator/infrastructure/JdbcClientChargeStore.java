package com.leandrossb.nummus.psp_simulator.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.application.ChargeStore;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedCharge;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientChargeStore implements ChargeStore {

  private final JdbcClient jdbc;

  public JdbcClientChargeStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public SimulatedCharge insert(SimulatedCharge charge) {
    jdbc.sql("""
        insert into psp_simulator.charge (public_id, amount, status, created_at, updated_at)
        values (:publicId, :amount, :status, :createdAt, :updatedAt)
        """)
        .param("publicId", charge.publicId())
        .param("amount", charge.amount().amount())
        .param("status", charge.status().name())
        .param("createdAt", toOffsetDateTime(charge.createdAt()))
        .param("updatedAt", toOffsetDateTime(charge.updatedAt()))
        .update();
    return charge;
  }

  @Override
  public Optional<SimulatedCharge> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, amount, status, created_at, updated_at
        from psp_simulator.charge where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapCharge(rs))
        .optional();
  }

  @Override
  public boolean transition(UUID publicId, ChargeStatus target) {
    int updated = jdbc.sql("""
        update psp_simulator.charge set status = :status, updated_at = now()
        where public_id = :publicId and status = 'PENDING'
        """)
        .param("status", target.name())
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  private SimulatedCharge mapCharge(ResultSet rs) throws SQLException {
    return new SimulatedCharge(
        rs.getObject("public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL")),
        ChargeStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
