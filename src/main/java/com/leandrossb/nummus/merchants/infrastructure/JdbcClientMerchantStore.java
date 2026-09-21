package com.leandrossb.nummus.merchants.infrastructure;

import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantStore;
import com.leandrossb.nummus.merchants.application.ResolvedMerchantKey;
import com.leandrossb.nummus.merchants.domain.ApiKey;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientMerchantStore implements MerchantStore {

  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcClientMerchantStore.class);

  private final JdbcClient jdbc;

  public JdbcClientMerchantStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Merchant insertMerchant(Merchant merchant, FeeSchedule fee) {
    jdbc.sql("""
        insert into merchants.merchant (public_id, name, created_at, fee_rate, fee_fixed)
        values (:publicId, :name, :createdAt, :rate, :fixed)
        """)
        .param("publicId", merchant.publicId())
        .param("name", merchant.name())
        .param("createdAt", toOffsetDateTime(merchant.createdAt()))
        .param("rate", fee.rate())
        .param("fixed", fee.fixedAmount())
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
  public Optional<FeeSchedule> findFeeSchedule(UUID merchantPublicId) {
    return jdbc.sql("select fee_rate, fee_fixed from merchants.merchant where public_id = :id")
        .param("id", merchantPublicId)
        .query((rs, i) -> new FeeSchedule(rs.getBigDecimal("fee_rate"), rs.getBigDecimal("fee_fixed")))
        .optional();
  }

  @Override
  public boolean updateFeeSchedule(UUID merchantPublicId, FeeSchedule fee) {
    return jdbc.sql("""
            update merchants.merchant set fee_rate = :rate, fee_fixed = :fixed
            where public_id = :id
            """)
        .param("rate", fee.rate())
        .param("fixed", fee.fixedAmount())
        .param("id", merchantPublicId)
        .update() == 1;
  }

  @Override
  public void insertApiKey(UUID merchantPublicId, String keyHash, String prefix, Duration expiresIn) {
    jdbc.sql("""
        insert into merchants.api_key (public_id, merchant_id, key_hash, prefix, expires_at)
        select :keyId, m.id, :keyHash, :prefix,
          case when :hasExpiry then now() + make_interval(secs => :expiresInSeconds) else null end
        from merchants.merchant m where m.public_id = :merchantPublicId
        """)
        .param("keyId", UUID.randomUUID())
        .param("keyHash", keyHash)
        .param("prefix", prefix)
        .param("merchantPublicId", merchantPublicId)
        .param("hasExpiry", expiresIn != null)
        .param("expiresInSeconds", expiresIn == null ? 0.0 : expiresIn.toMillis() / 1000.0)
        .update();
  }

  @Override
  public Optional<ApiKey> findActiveKeyByHash(String keyHash) {
    return jdbc.sql("""
        select public_id, prefix, status, created_at, expires_at, last_used_at from merchants.api_key
        where key_hash = :keyHash and status = 'ACTIVE'
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> mapKey(rs)).optional();
  }

  @Override
  public List<ApiKey> listKeys(UUID merchantPublicId) {
    return jdbc.sql("""
        select k.public_id, k.prefix, k.status, k.created_at, k.expires_at, k.last_used_at
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
  public Optional<Instant> retireApiKey(UUID merchantPublicId, UUID keyPublicId, Duration grace) {
    return jdbc.sql("""
        update merchants.api_key k set expires_at =
            least(coalesce(k.expires_at, 'infinity'::timestamptz),
                  now() + make_interval(secs => :graceSeconds))
        from merchants.merchant m
        where k.merchant_id = m.id and m.public_id = :merchantPublicId
          and k.public_id = :keyPublicId and k.status = 'ACTIVE'
        returning k.expires_at
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("keyPublicId", keyPublicId)
        .param("graceSeconds", grace.toMillis() / 1000.0)
        .query((rs, i) -> rs.getObject("expires_at", OffsetDateTime.class).toInstant())
        .optional();
  }

  @Override
  public Optional<Instant> retireOperatorKey(UUID keyPublicId, Duration grace) {
    return jdbc.sql("""
        update merchants.operator_key set expires_at =
            least(coalesce(expires_at, 'infinity'::timestamptz),
                  now() + make_interval(secs => :graceSeconds))
        where public_id = :keyPublicId and status = 'ACTIVE'
        returning expires_at
        """)
        .param("keyPublicId", keyPublicId)
        .param("graceSeconds", grace.toMillis() / 1000.0)
        .query((rs, i) -> rs.getObject("expires_at", OffsetDateTime.class).toInstant())
        .optional();
  }

  @Override
  public Optional<ResolvedMerchantKey> findMerchantByKeyHash(String keyHash) {
    return jdbc.sql("""
        select m.public_id, m.name, m.created_at, k.public_id as key_public_id
        from merchants.merchant m join merchants.api_key k on k.merchant_id = m.id
        where k.key_hash = :keyHash and k.status = 'ACTIVE'
          and (k.expires_at is null or k.expires_at > now())
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> new ResolvedMerchantKey(mapMerchant(rs),
            rs.getObject("key_public_id", UUID.class))).optional();
  }

  @Override
  public void stampApiKeyLastUsed(String keyHash) {
    try {
      jdbc.sql("update merchants.api_key set last_used_at = now() where key_hash = :keyHash")
          .param("keyHash", keyHash).update();
    } catch (DataAccessException e) {
      LOGGER.warn("failed to stamp api key last_used_at", e);
    }
  }

  @Override
  public void stampOperatorKeyLastUsed(String keyHash) {
    try {
      jdbc.sql("update merchants.operator_key set last_used_at = now() where key_hash = :keyHash")
          .param("keyHash", keyHash).update();
    } catch (DataAccessException e) {
      LOGGER.warn("failed to stamp operator key last_used_at", e);
    }
  }

  @Override
  public void insertOperatorKey(String keyHash, String prefix, Duration expiresIn) {
    jdbc.sql("""
        insert into merchants.operator_key (public_id, key_hash, prefix, expires_at)
        values (:keyId, :keyHash, :prefix,
          case when :hasExpiry then now() + make_interval(secs => :expiresInSeconds) else null end)
        """)
        .param("keyId", UUID.randomUUID())
        .param("keyHash", keyHash)
        .param("prefix", prefix)
        .param("hasExpiry", expiresIn != null)
        .param("expiresInSeconds", expiresIn == null ? 0.0 : expiresIn.toMillis() / 1000.0)
        .update();
  }

  @Override
  public Optional<ApiKey> findActiveOperatorKeyByHash(String keyHash) {
    return jdbc.sql("""
        select public_id, prefix, status, created_at, expires_at, last_used_at
        from merchants.operator_key
        where key_hash = :keyHash and status = 'ACTIVE'
          and (expires_at is null or expires_at > now())
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> mapKey(rs)).optional();
  }

  @Override
  public List<ApiKey> listOperatorKeys() {
    return jdbc.sql("""
        select public_id, prefix, status, created_at, expires_at, last_used_at
        from merchants.operator_key
        order by id desc
        """)
        .query((rs, i) -> mapKey(rs)).list();
  }

  @Override
  public boolean revokeOperatorKey(UUID keyPublicId) {
    return jdbc.sql("""
        update merchants.operator_key set status = 'REVOKED'
        where public_id = :keyPublicId and status = 'ACTIVE'
        """)
        .param("keyPublicId", keyPublicId)
        .update() == 1;
  }

  @Override
  public boolean hasActiveOperatorKey() {
    return jdbc.sql("""
        select exists(select 1 from merchants.operator_key where status = 'ACTIVE')
        """)
        .query(Boolean.class).single();
  }

  private static Merchant mapMerchant(ResultSet rs) throws SQLException {
    return new Merchant(rs.getObject("public_id", UUID.class), rs.getString("name"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static ApiKey mapKey(ResultSet rs) throws SQLException {
    return new ApiKey(rs.getObject("public_id", UUID.class), rs.getString("prefix"),
        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        instantOrNull(rs, "expires_at"), instantOrNull(rs, "last_used_at"));
  }

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
    return instant.atOffset(java.time.ZoneOffset.UTC);
  }
}
