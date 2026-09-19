package com.leandrossb.nummus.interfaces.idempotency.infrastructure;

import com.leandrossb.nummus.interfaces.idempotency.IdempotencyStore;
import com.leandrossb.nummus.interfaces.idempotency.StoredResponse;
import com.leandrossb.nummus.interfaces.idempotency.StoredRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientIdempotencyStore implements IdempotencyStore {

  private final JdbcClient jdbc;

  public JdbcClientIdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(UUID merchantPublicId, String key, byte[] requestFingerprint, Instant expiresAt) {
    jdbc.sql("""
        insert into idempotency.idempotency_keys (merchant_public_id, key, request_fingerprint, expires_at)
        values (:merchant, :key, :fingerprint, :expiresAt)
        """)
        .param("merchant", merchantPublicId)
        .param("key", key)
        .param("fingerprint", requestFingerprint)
        .param("expiresAt", toOffsetDateTime(expiresAt))
        .update();
  }

  @Override
  public Optional<StoredRow> findByKey(UUID merchantPublicId, String key) {
    return jdbc.sql("""
        select request_fingerprint, expires_at, response_status,
               response_content_type, response_location, response_body
        from idempotency.idempotency_keys
        where merchant_public_id is not distinct from :merchant::uuid
          and key = :key
        """)
        .param("merchant", merchantPublicId)
        .param("key", key)
        .query((rs, i) -> mapRow(rs))
        .optional();
  }

  @Override
  public boolean attachResponse(String key, StoredResponse response) {
    return jdbc.sql("""
        update idempotency.idempotency_keys
        set response_status = :status, response_content_type = :contentType,
            response_location = :location, response_body = :body
        where key = :key and response_status is null
        """)
        .param("status", response.status())
        .param("contentType", response.contentType())
        .param("location", response.location())
        .param("body", response.body())
        .param("key", key)
        .update() == 1;
  }

  @Override
  public boolean reclaimExpired(String key, byte[] newFingerprint, Instant newExpiresAt) {
    return jdbc.sql("""
        update idempotency.idempotency_keys
        set request_fingerprint = :fingerprint, expires_at = :expiresAt,
            response_status = null, response_content_type = null,
            response_location = null, response_body = null
        where key = :key and expires_at <= now()
        """)
        .param("fingerprint", newFingerprint)
        .param("expiresAt", toOffsetDateTime(newExpiresAt))
        .param("key", key)
        .update() == 1;
  }

  @Override
  public int purgeExpired(Instant now) {
    return jdbc.sql("delete from idempotency.idempotency_keys where expires_at <= :now")
        .param("now", toOffsetDateTime(now))
        .update();
  }

  private static StoredRow mapRow(ResultSet rs) throws SQLException {
    StoredResponse response = rs.getObject(3) == null ? null
        : new StoredResponse(rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6));
    return new StoredRow(rs.getBytes(1), toInstant(rs.getObject(2, OffsetDateTime.class)), response);
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant.atOffset(java.time.ZoneOffset.UTC);
  }

  private static Instant toInstant(OffsetDateTime timestamp) {
    return timestamp.toInstant();
  }
}
