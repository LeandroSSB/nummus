package com.leandrossb.nummus.ledger.infrastructure;

import com.leandrossb.nummus.ledger.application.LedgerRepository;
import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.StatementLine;
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
public class JdbcClientLedgerRepository implements LedgerRepository {

  private final JdbcClient jdbc;

  public JdbcClientLedgerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public LedgerAccount insertAccount(LedgerAccount account) {
    jdbc.sql("""
        insert into ledger.ledger_account (public_id, name, type, currency, status, opened_at, closed_at)
        values (:publicId, :name, :type, :currency, :status, :openedAt, :closedAt)
        """)
        .param("publicId", account.publicId())
        .param("name", account.name())
        .param("type", account.type().name())
        .param("currency", account.currency().getCurrencyCode())
        .param("status", account.status().name())
        .param("openedAt", toOffsetDateTime(account.openedAt()))
        .param("closedAt", account.closedAt() == null ? null : toOffsetDateTime(account.closedAt()))
        .update();
    return account;
  }

  @Override
  public Optional<LedgerAccount> findAccount(UUID publicId) {
    return jdbc.sql("""
        select public_id, name, type, currency, status, opened_at, closed_at
        from ledger.ledger_account where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapAccount(rs))
        .optional();
  }

  @Override
  public boolean updateAccountStatus(UUID publicId, AccountStatus status, Instant closedAt) {
    int updated = jdbc.sql("""
        update ledger.ledger_account set status = :status, closed_at = :closedAt
        where public_id = :publicId
        """)
        .param("status", status.name())
        .param("closedAt", closedAt == null ? null : toOffsetDateTime(closedAt))
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  // ------------------------------------------------------------------
  // Journal operations: implemented in Task 11.

  @Override
  public PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId,
      List<PostingDraft> postings) {
    throw new UnsupportedOperationException("journal writes arrive with Task 11");
  }

  @Override
  public Optional<PostedTransaction> findTransaction(UUID publicId) {
    throw new UnsupportedOperationException("journal reads arrive with Task 11");
  }

  @Override
  public BigDecimal rawBalance(UUID accountPublicId) {
    throw new UnsupportedOperationException("balance derivation arrives with Task 11");
  }

  @Override
  public List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit) {
    throw new UnsupportedOperationException("statements arrive with Task 11");
  }

  // ------------------------------------------------------------------

  private LedgerAccount mapAccount(ResultSet rs) throws SQLException {
    OffsetDateTime closedAt = rs.getObject("closed_at", OffsetDateTime.class);
    return new LedgerAccount(
        rs.getObject("public_id", UUID.class),
        rs.getString("name"),
        AccountType.valueOf(rs.getString("type")),
        Currency.getInstance(rs.getString("currency").trim()),
        AccountStatus.valueOf(rs.getString("status")),
        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
        closedAt == null ? null : closedAt.toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
