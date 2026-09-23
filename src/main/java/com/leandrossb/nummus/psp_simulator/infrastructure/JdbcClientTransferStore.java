package com.leandrossb.nummus.psp_simulator.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.application.TransferStore;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedTransfer;
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
public class JdbcClientTransferStore implements TransferStore {

  private final JdbcClient jdbc;

  public JdbcClientTransferStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public SimulatedTransfer insert(SimulatedTransfer transfer) {
    jdbc.sql("""
        insert into psp_simulator.payout_transfer
            (public_id, amount, destination_bank_key, status, created_at, updated_at)
        values (:publicId, :amount, :destinationBankKey, :status, :createdAt, :updatedAt)
        """)
        .param("publicId", transfer.publicId())
        .param("amount", transfer.amount().amount())
        .param("destinationBankKey", transfer.destinationBankKey())
        .param("status", transfer.status().name())
        .param("createdAt", toOffsetDateTime(transfer.createdAt()))
        .param("updatedAt", toOffsetDateTime(transfer.updatedAt()))
        .update();
    return transfer;
  }

  @Override
  public Optional<SimulatedTransfer> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, amount, destination_bank_key, status, created_at, updated_at
        from psp_simulator.payout_transfer where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapTransfer(rs))
        .optional();
  }

  @Override
  public boolean transition(UUID publicId, ChargeStatus target) {
    int updated = jdbc.sql("""
        update psp_simulator.payout_transfer set status = :status, updated_at = now()
        where public_id = :publicId and status = 'PENDING'
        """)
        .param("status", target.name())
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  @Override
  public List<SimulatedTransfer> findSucceededBetween(Instant from, Instant to) {
    return jdbc.sql("""
        select public_id, amount, destination_bank_key, status, created_at, updated_at
        from psp_simulator.payout_transfer
        where status = 'SUCCEEDED' and updated_at >= :from and updated_at < :to
        order by updated_at, id
        """)
        .param("from", toOffsetDateTime(from))
        .param("to", toOffsetDateTime(to))
        .query((rs, i) -> mapTransfer(rs))
        .list();
  }

  private SimulatedTransfer mapTransfer(ResultSet rs) throws SQLException {
    return new SimulatedTransfer(
        rs.getObject("public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL")),
        rs.getString("destination_bank_key"),
        ChargeStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
