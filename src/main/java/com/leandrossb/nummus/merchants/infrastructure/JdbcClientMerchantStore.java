package com.leandrossb.nummus.merchants.infrastructure;

import com.leandrossb.nummus.merchants.application.MerchantStore;
import com.leandrossb.nummus.merchants.domain.ApiKey;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientMerchantStore implements MerchantStore {

  private final JdbcClient jdbc;

  public JdbcClientMerchantStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Merchant insertMerchant(Merchant merchant) {
    jdbc.sql("""
        insert into merchants.merchant (public_id, name, created_at)
        values (:publicId, :name, :createdAt)
        """)
        .param("publicId", merchant.publicId())
        .param("name", merchant.name())
        .param("createdAt", toOffsetDateTime(merchant.createdAt()))
        .update();
    return merchant;
  }

  @Override
  public Optional<Merchant> findMerchant(UUID publicId) {
    return jdbc.sql("""
        select public_id, name, created_at from merchants.merchant where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapMerchant(rs)).optional();
  }

  @Override
  public void insertApiKey(UUID merchantPublicId, String keyHash, String prefix) {
    jdbc.sql("""
        insert into merchants.api_key (public_id, merchant_id, key_hash, prefix)
        select :keyId, m.id, :keyHash, :prefix
        from merchants.merchant m where m.public_id = :merchantPublicId
        """)
        .param("keyId", UUID.randomUUID())
        .param("keyHash", keyHash)
        .param("prefix", prefix)
        .param("merchantPublicId", merchantPublicId)
        .update();
  }

  @Override
  public Optional<ApiKey> findActiveKeyByHash(String keyHash) {
    return jdbc.sql("""
        select public_id, prefix, status, created_at from merchants.api_key
        where key_hash = :keyHash and status = 'ACTIVE'
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> mapKey(rs)).optional();
  }

  @Override
  public List<ApiKey> listKeys(UUID merchantPublicId) {
    return jdbc.sql("""
        select k.public_id, k.prefix, k.status, k.created_at
        from merchants.api_key k join merchants.merchant m on m.id = k.merchant_id
        where m.public_id = :merchantPublicId order by k.id desc
        """)
        .param("merchantPublicId", merchantPublicId)
        .query((rs, i) -> mapKey(rs)).list();
  }

  @Override
  public boolean revokeApiKey(UUID merchantPublicId, UUID keyPublicId) {
    return jdbc.sql("""
        update merchants.api_key k set status = 'REVOKED'
        from merchants.merchant m
        where k.merchant_id = m.id and m.public_id = :merchantPublicId
          and k.public_id = :keyPublicId and k.status = 'ACTIVE'
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("keyPublicId", keyPublicId)
        .update() == 1;
  }

  @Override
  public Optional<Merchant> findMerchantByKeyHash(String keyHash) {
    return jdbc.sql("""
        select m.public_id, m.name, m.created_at
        from merchants.merchant m join merchants.api_key k on k.merchant_id = m.id
        where k.key_hash = :keyHash and k.status = 'ACTIVE'
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> mapMerchant(rs)).optional();
  }

  private static Merchant mapMerchant(ResultSet rs) throws SQLException {
    return new Merchant(rs.getObject("public_id", UUID.class), rs.getString("name"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static ApiKey mapKey(ResultSet rs) throws SQLException {
    return new ApiKey(rs.getObject("public_id", UUID.class), rs.getString("prefix"),
        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
    return instant.atOffset(java.time.ZoneOffset.UTC);
  }
}
