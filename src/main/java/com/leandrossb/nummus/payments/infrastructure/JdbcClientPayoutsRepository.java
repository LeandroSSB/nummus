package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PayoutsRepository;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import java.math.BigDecimal;
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
public class JdbcClientPayoutsRepository implements PayoutsRepository {

  private final JdbcClient jdbc;

  public JdbcClientPayoutsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Payout insert(Payout payout) {
    jdbc.sql("""
        insert into payments.payout
          (public_id, account_public_id, amount, destination_bank_key, status,
           transfer_public_id, expires_at, created_at, request_transaction_public_id)
        values (:publicId, :accountPublicId, :amount, :destinationBankKey, :status,
                :transferPublicId, :expiresAt, :createdAt, :requestTransactionPublicId)
        """)
        .param("publicId", payout.publicId())
        .param("accountPublicId", payout.accountPublicId())
        .param("amount", payout.amount().amount())
        .param("destinationBankKey", payout.destinationBankKey())
        .param("status", payout.status().name())
        .param("transferPublicId", payout.transferPublicId())
        .param("expiresAt", toOffsetDateTime(payout.expiresAt()))
        .param("createdAt", toOffsetDateTime(payout.createdAt()))
        .param("requestTransactionPublicId", payout.requestTransactionPublicId())
        .update();
    return payout;
  }

  @Override
  public Optional<Payout> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, account_public_id, amount, destination_bank_key, status,
               transfer_public_id, expires_at, created_at, settled_at, fee_amount,
               request_transaction_public_id, execute_transaction_public_id,
               return_transaction_public_id
        from payments.payout where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapPayout(rs))
        .optional();
  }

  private Payout mapPayout(ResultSet rs) throws SQLException {
    OffsetDateTime settledAt = rs.getObject("settled_at", OffsetDateTime.class);
    BigDecimal feeAmount = rs.getBigDecimal("fee_amount");
    return new Payout(
        rs.getObject("public_id", UUID.class),
        rs.getObject("account_public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL")),
        PayoutStatus.valueOf(rs.getString("status")),
        rs.getString("destination_bank_key"),
        rs.getObject("transfer_public_id", UUID.class),
        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        settledAt == null ? null : settledAt.toInstant(),
        feeAmount == null ? null : Money.of(feeAmount, Currency.getInstance("BRL")),
        rs.getObject("request_transaction_public_id", UUID.class),
        rs.getObject("execute_transaction_public_id", UUID.class),
        rs.getObject("return_transaction_public_id", UUID.class));
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
