package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.TransfersRepository;
import com.leandrossb.nummus.payments.domain.Transfer;
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
public class JdbcClientTransfersRepository implements TransfersRepository {

  private final JdbcClient jdbc;

  public JdbcClientTransfersRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Transfer insert(Transfer transfer) {
    jdbc.sql("""
        insert into payments.transfer
          (public_id, merchant_public_id, from_account_public_id, to_account_public_id,
           amount, journal_transaction_public_id, created_at)
        values (:publicId, :merchantPublicId, :fromAccountPublicId, :toAccountPublicId,
                :amount, :journalTransactionPublicId, :createdAt)
        """)
        .param("publicId", transfer.publicId())
        .param("merchantPublicId", transfer.merchantPublicId())
        .param("fromAccountPublicId", transfer.fromAccountPublicId())
        .param("toAccountPublicId", transfer.toAccountPublicId())
        .param("amount", transfer.amount().amount())
        .param("journalTransactionPublicId", transfer.journalTransactionPublicId())
        .param("createdAt", toOffsetDateTime(transfer.createdAt()))
        .update();
    return transfer;
  }

  @Override
  public Optional<Transfer> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, merchant_public_id, from_account_public_id, to_account_public_id,
               amount, journal_transaction_public_id, created_at
        from payments.transfer where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapTransfer(rs))
        .optional();
  }

  @Override
  public List<Transfer> listByMerchant(UUID merchantPublicId, UUID after, int limit) {
    return jdbc.sql("""
        select public_id, merchant_public_id, from_account_public_id, to_account_public_id,
               amount, journal_transaction_public_id, created_at
        from payments.transfer
        where merchant_public_id = :merchantPublicId
          and (:after::uuid is null
               or id < (select t2.id from payments.transfer t2 where t2.public_id = :after))
        order by id desc
        limit :limit
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("after", after)
        .param("limit", limit)
        .query((rs, i) -> mapTransfer(rs))
        .list();
  }

  private Transfer mapTransfer(ResultSet rs) throws SQLException {
    return new Transfer(
        rs.getObject("public_id", UUID.class),
        rs.getObject("merchant_public_id", UUID.class),
        rs.getObject("from_account_public_id", UUID.class),
        rs.getObject("to_account_public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL")),
        rs.getObject("journal_transaction_public_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
