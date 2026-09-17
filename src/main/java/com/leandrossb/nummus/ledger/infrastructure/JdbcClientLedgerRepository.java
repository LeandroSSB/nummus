package com.leandrossb.nummus.ledger.infrastructure;

import com.leandrossb.nummus.ledger.application.LedgerRepository;
import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
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
import org.springframework.transaction.annotation.Transactional;

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
  // Journal operations

  @Override
  @Transactional
  public PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId,
      List<PostingDraft> postings) {
    UUID txPublicId = UUID.randomUUID();
    Instant bookedAt = Instant.now();
    Long txInternalId;
    if (reversalOfPublicId == null) {
      txInternalId = jdbc.sql("""
          insert into ledger.journal_transaction (public_id, memo, booked_at)
          values (:publicId, :memo, :bookedAt)
          returning id
          """)
          .param("publicId", txPublicId)
          .param("memo", memo)
          .param("bookedAt", toOffsetDateTime(bookedAt))
          .query((rs, i) -> rs.getLong(1))
          .single();
    } else {
      txInternalId = jdbc.sql("""
          insert into ledger.journal_transaction (public_id, memo, booked_at, reversal_of)
          values (:publicId, :memo, :bookedAt,
                  (select id from ledger.journal_transaction where public_id = :reversalOf))
          returning id
          """)
          .param("publicId", txPublicId)
          .param("memo", memo)
          .param("bookedAt", toOffsetDateTime(bookedAt))
          .param("reversalOf", reversalOfPublicId)
          .query((rs, i) -> rs.getLong(1))
          .single();
    }
    for (PostingDraft draft : postings) {
      int inserted = jdbc.sql("""
          insert into ledger.journal_posting (transaction_id, account_id, direction, amount)
          select :txInternalId, a.id, :direction, :amount
          from ledger.ledger_account a
          where a.public_id = :accountPublicId
          """)
          .param("txInternalId", txInternalId)
          .param("direction", draft.direction().name())
          .param("amount", draft.amount().amount())
          .param("accountPublicId", draft.accountPublicId())
          .update();
      if (inserted != 1) {
        throw new com.leandrossb.nummus.ledger.domain.UnknownAccountException(draft.accountPublicId());
      }
    }
    List<PostedPosting> posted = postings.stream()
        .map(d -> new PostedPosting(d.accountPublicId(), d.direction(), d.amount()))
        .toList();
    return new PostedTransaction(txPublicId, memo, bookedAt, reversalOfPublicId, posted);
  }

  @Override
  public Optional<PostedTransaction> findTransaction(UUID publicId) {
    record Row(UUID accountPublicId, Direction direction, BigDecimal amount) {}
    List<Row> rows = jdbc.sql("""
        select a.public_id as account_public_id, p.direction, p.amount
        from ledger.journal_transaction t
        left join ledger.journal_transaction r on r.id = t.reversal_of
        join ledger.journal_posting p on p.transaction_id = t.id
        join ledger.ledger_account a on a.id = p.account_id
        where t.public_id = :publicId
        order by p.id
        """)
        .param("publicId", publicId)
        .query((rs, i) -> new Row(
            rs.getObject("account_public_id", UUID.class),
            Direction.valueOf(rs.getString("direction")),
            rs.getBigDecimal("amount")))
        .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return jdbc.sql("""
        select t.public_id, t.memo, t.booked_at, r.public_id as reversal_public_id
        from ledger.journal_transaction t
        left join ledger.journal_transaction r on r.id = t.reversal_of
        where t.public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> {
          PostedTransaction tx = new PostedTransaction(
              rs.getObject("public_id", UUID.class),
              rs.getString("memo"),
              rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
              rs.getObject("reversal_public_id", UUID.class),
              rows.stream()
                  .map(row -> new PostedPosting(row.accountPublicId(), row.direction(),
                      Money.of(row.amount(), Currency.getInstance("BRL"))))
                  .toList());
          return tx;
        })
        .optional();
  }

  @Override
  public BigDecimal rawBalance(UUID accountPublicId) {
    return jdbc.sql("""
        select coalesce(sum(case p.direction when 'DEBIT' then p.amount else -p.amount end), 0) as balance
        from ledger.journal_posting p
        where p.account_id = (select id from ledger.ledger_account where public_id = :publicId)
        """)
        .param("publicId", accountPublicId)
        .query((rs, i) -> rs.getBigDecimal("balance"))
        .single();
  }

  @Override
  public List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit) {
    return jdbc.sql("""
        select t.booked_at, t.public_id as transaction_public_id, t.memo, p.direction, p.amount
        from ledger.journal_posting p
        join ledger.journal_transaction t on t.id = p.transaction_id
        where p.account_id = (select id from ledger.ledger_account where public_id = :publicId)
        order by t.booked_at desc, p.id desc
        limit :limit offset :offset
        """)
        .param("publicId", accountPublicId)
        .param("limit", limit)
        .param("offset", offset)
        .query((rs, i) -> new StatementLine(
            rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
            rs.getObject("transaction_public_id", UUID.class),
            rs.getString("memo"),
            Direction.valueOf(rs.getString("direction")),
            Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL"))))
        .list();
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
