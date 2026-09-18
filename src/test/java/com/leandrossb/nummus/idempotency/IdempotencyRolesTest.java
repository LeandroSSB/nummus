package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The suite itself connects as the Testcontainers superuser; production runs
 * as nummus_app, so the V7 grants (full row lifecycle on
 * idempotency.idempotency_keys) are exercised here through the same SQL the
 * store issues. PER_CLASS so the Spring context (and Flyway's V7) is up
 * before the non-static @BeforeAll grants the app role a login — same reason
 * as AccountsRolesTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleRunsTheReserveAttachReclaimLifecycle() throws Exception {
    String key = UUID.randomUUID().toString();
    try (Connection app = appConnection()) {
      try (PreparedStatement st = app.prepareStatement("""
          insert into idempotency.idempotency_keys (key, request_fingerprint, expires_at)
          values (?, ?, ?)
          """)) {
        st.setString(1, key);
        st.setBytes(2, new byte[] {1});
        st.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(3600)));
        assertEquals(1, st.executeUpdate());
      }
      try (PreparedStatement st = app.prepareStatement("""
          update idempotency.idempotency_keys
          set response_status = ?, response_content_type = ?, response_location = ?, response_body = ?
          where key = ? and response_status is null
          """)) {
        st.setInt(1, 201);
        st.setString(2, "application/json");
        st.setString(3, null);
        st.setString(4, "{\"publicId\":\"irrelevant\"}");
        st.setString(5, key);
        assertEquals(1, st.executeUpdate());
      }
      // Age the row past its expiry, then reclaim it as the store does.
      try (PreparedStatement st = app.prepareStatement(
          "update idempotency.idempotency_keys set expires_at = now() - interval '1 minute' where key = ?")) {
        st.setString(1, key);
        assertEquals(1, st.executeUpdate());
      }
      byte[] reclaimedFingerprint = new byte[] {2};
      try (PreparedStatement st = app.prepareStatement("""
          update idempotency.idempotency_keys
          set request_fingerprint = ?, expires_at = ?,
              response_status = null, response_content_type = null,
              response_location = null, response_body = null
          where key = ? and expires_at <= now()
          """)) {
        st.setBytes(1, reclaimedFingerprint);
        st.setTimestamp(2, Timestamp.from(Instant.now().plusSeconds(3600)));
        st.setString(3, key);
        assertEquals(1, st.executeUpdate());
      }
      try (PreparedStatement st = app.prepareStatement(
          "select request_fingerprint, response_status from idempotency.idempotency_keys where key = ?")) {
        st.setString(1, key);
        try (ResultSet rs = st.executeQuery()) {
          assertTrue(rs.next());
          assertArrayEquals(reclaimedFingerprint, rs.getBytes(1));
          assertFalse(rs.getObject(2) != null, "reclaim must clear the stored response");
        }
      }
    }
  }

  @Test
  void appRoleCanPurgeExpiredRowsOnly() throws Exception {
    String expired = UUID.randomUUID().toString();
    String live = UUID.randomUUID().toString();
    try (Connection app = appConnection()) {
      try (PreparedStatement st = app.prepareStatement("""
          insert into idempotency.idempotency_keys (key, request_fingerprint, expires_at)
          values (?, ?, ?)
          """)) {
        st.setString(1, expired);
        st.setBytes(2, new byte[] {1});
        st.setTimestamp(3, Timestamp.from(Instant.now().minusSeconds(60)));
        assertEquals(1, st.executeUpdate());
        st.setString(1, live);
        st.setBytes(2, new byte[] {2});
        st.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(3600)));
        assertEquals(1, st.executeUpdate());
      }
      // The DELETE the purge job uniquely needs, with the store's predicate.
      try (PreparedStatement st = app.prepareStatement(
          "delete from idempotency.idempotency_keys where expires_at <= ?")) {
        st.setTimestamp(1, Timestamp.from(Instant.now()));
        assertEquals(1, st.executeUpdate());
      }
      try (PreparedStatement st = app.prepareStatement(
          "select count(*) from idempotency.idempotency_keys where key = ?")) {
        st.setString(1, live);
        try (ResultSet rs = st.executeQuery()) {
          rs.next();
          assertEquals(1, rs.getInt(1), "the not-yet-expired row must survive the purge");
        }
      }
    }
  }
}
