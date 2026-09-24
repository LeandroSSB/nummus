package com.leandrossb.nummus.merchants.infrastructure;

import com.leandrossb.nummus.merchants.application.BankAccountStore;
import com.leandrossb.nummus.merchants.domain.BankAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientBankAccountStore implements BankAccountStore {

  private static final String COLUMNS = """
      public_id, merchant_public_id, bank_code, branch, account_number, holder_tax_id,
      status, created_at, verified_at
      """;

  private final JdbcClient jdbc;

  public JdbcClientBankAccountStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public BankAccount insert(BankAccount account, String verificationCodeHash) {
    jdbc.sql("""
        insert into merchants.bank_account
          (public_id, merchant_public_id, bank_code, branch, account_number, holder_tax_id,
           status, verification_code_hash, created_at)
        values (:publicId, :merchantPublicId, :bankCode, :branch, :accountNumber, :holderTaxId,
                :status, :codeHash, :createdAt)
        """)
        .param("publicId", account.publicId())
        .param("merchantPublicId", account.merchantPublicId())
        .param("bankCode", account.bankCode())
        .param("branch", account.branch())
        .param("accountNumber", account.accountNumber())
        .param("holderTaxId", account.holderTaxId())
        .param("status", account.status())
        .param("codeHash", verificationCodeHash)
        .param("createdAt", toOffsetDateTime(account.createdAt()))
        .update();
    return account;
  }

  @Override
  public Optional<BankAccount> findByPublicIdAndMerchant(UUID merchantPublicId, UUID publicId) {
    return jdbc.sql("select " + COLUMNS + " from merchants.bank_account"
        + " where public_id = :publicId and merchant_public_id = :merchantPublicId")
        .param("publicId", publicId)
        .param("merchantPublicId", merchantPublicId)
        .query((rs, i) -> mapAccount(rs))
        .optional();
  }

  @Override
  public List<BankAccount> listByMerchant(UUID merchantPublicId, int limit) {
    return jdbc.sql("select " + COLUMNS + " from merchants.bank_account"
        + " where merchant_public_id = :merchantPublicId order by id desc limit :limit")
        .param("merchantPublicId", merchantPublicId)
        .param("limit", limit)
        .query((rs, i) -> mapAccount(rs))
        .list();
  }

  @Override
  public boolean verify(UUID merchantPublicId, UUID publicId, String verificationCodeHash) {
    int updated = jdbc.sql("""
        update merchants.bank_account
        set status = 'VERIFIED', verified_at = now()
        where public_id = :publicId and merchant_public_id = :merchantPublicId
          and status = 'PENDING_VERIFICATION' and verification_code_hash = :codeHash
        """)
        .param("publicId", publicId)
        .param("merchantPublicId", merchantPublicId)
        .param("codeHash", verificationCodeHash)
        .update();
    return updated == 1;
  }

  @Override
  public boolean revoke(UUID merchantPublicId, UUID publicId) {
    int updated = jdbc.sql("""
        update merchants.bank_account
        set status = 'REVOKED'
        where public_id = :publicId and merchant_public_id = :merchantPublicId
          and status in ('PENDING_VERIFICATION', 'VERIFIED')
        """)
        .param("publicId", publicId)
        .param("merchantPublicId", merchantPublicId)
        .update();
    return updated == 1;
  }

  private BankAccount mapAccount(ResultSet rs) throws SQLException {
    OffsetDateTime verifiedAt = rs.getObject("verified_at", OffsetDateTime.class);
    return new BankAccount(rs.getObject("public_id", UUID.class),
        rs.getObject("merchant_public_id", UUID.class), rs.getString("bank_code"),
        rs.getString("branch"), rs.getString("account_number"), rs.getString("holder_tax_id"),
        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        verifiedAt == null ? null : verifiedAt.toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
