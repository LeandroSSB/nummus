package com.leandrossb.nummus.accounts.infrastructure;

import com.leandrossb.nummus.accounts.application.AccountsRepository;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientAccountsRepository implements AccountsRepository {

  private final JdbcClient jdbc;

  public JdbcClientAccountsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PaymentAccount insert(PaymentAccount account) {
    jdbc.sql("""
        insert into accounts.payment_account
          (public_id, holder_name, status, ledger_account_public_id, opened_at, closed_at)
        values (:publicId, :holderName, :status, :ledgerAccountPublicId, :openedAt, :closedAt)
        """)
        .param("publicId", account.publicId())
        .param("holderName", account.holderName())
        .param("status", account.status().name())
        .param("ledgerAccountPublicId", account.ledgerAccountPublicId())
        .param("openedAt", toOffsetDateTime(account.openedAt()))
        .param("closedAt", account.closedAt() == null ? null : toOffsetDateTime(account.closedAt()))
        .update();
    return account;
  }

  @Override
  public Optional<PaymentAccount> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, holder_name, status, ledger_account_public_id, opened_at, closed_at
        from accounts.payment_account where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapPaymentAccount(rs))
        .optional();
  }

  @Override
  public boolean updateStatus(UUID publicId, AccountStatus status, Instant closedAt) {
    int updated = jdbc.sql("""
        update accounts.payment_account set status = :status, closed_at = :closedAt
        where public_id = :publicId
        """)
        .param("status", status.name())
        .param("closedAt", closedAt == null ? null : toOffsetDateTime(closedAt))
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  private PaymentAccount mapPaymentAccount(ResultSet rs) throws SQLException {
    OffsetDateTime closedAt = rs.getObject("closed_at", OffsetDateTime.class);
    return new PaymentAccount(
        rs.getObject("public_id", UUID.class),
        rs.getString("holder_name"),
        AccountStatus.valueOf(rs.getString("status")),
        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
        closedAt == null ? null : closedAt.toInstant(),
        rs.getObject("ledger_account_public_id", UUID.class));
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
