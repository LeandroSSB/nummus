package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyLifecycleSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void lifecycleColumnsExistAndStayNullByDefault() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      String merchantId = UUID.randomUUID().toString();
      st.executeUpdate("insert into merchants.merchant (public_id, name) values ('"
          + merchantId + "', 'lifecycle probe')");
      st.executeUpdate("insert into merchants.api_key (merchant_id, key_hash, prefix) "
          + "select id, 'lifecycle-hash-1', 'nummus_s' from merchants.merchant "
          + "where public_id = '" + merchantId + "'");
      st.executeUpdate("insert into merchants.operator_key (key_hash, prefix) "
          + "values ('lifecycle-hash-2', 'nummus_s')");

      try (ResultSet rs = st.executeQuery(
          "select expires_at, last_used_at from merchants.api_key where key_hash = 'lifecycle-hash-1'")) {
        assertTrue(rs.next());
        assertEquals(null, rs.getObject("expires_at"));
        assertEquals(null, rs.getObject("last_used_at"));
      }
      try (ResultSet rs = st.executeQuery(
          "select expires_at, last_used_at from merchants.operator_key where key_hash = 'lifecycle-hash-2'")) {
        assertTrue(rs.next());
        assertEquals(null, rs.getObject("expires_at"));
        assertEquals(null, rs.getObject("last_used_at"));
      }
    }
  }

  @Test
  void expiresAtComparesAndLastUsedAtUpdates() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.operator_key (key_hash, prefix, expires_at) "
          + "values ('lifecycle-hash-3', 'nummus_s', now() - interval '1 hour')");
      try (ResultSet rs = st.executeQuery("select expires_at < now() as already_past "
          + "from merchants.operator_key where key_hash = 'lifecycle-hash-3'")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("already_past"));
      }
      assertEquals(1, st.executeUpdate("update merchants.operator_key set last_used_at = now() "
          + "where key_hash = 'lifecycle-hash-3'"));
      try (ResultSet rs = st.executeQuery("select last_used_at is not null as stamped "
          + "from merchants.operator_key where key_hash = 'lifecycle-hash-3'")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("stamped"));
      }
    }
  }

  @Test
  void appRoleUpdatesTheLifecycleColumnsWithoutNewGrants() throws Exception {
    String merchantId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.merchant (public_id, name) values ('"
          + merchantId + "', 'grant probe')");
      st.executeUpdate("insert into merchants.api_key (merchant_id, key_hash, prefix) "
          + "select id, 'lifecycle-hash-4', 'nummus_s' from merchants.merchant "
          + "where public_id = '" + merchantId + "'");
    }
    // V10/V11 already grant UPDATE on both key tables; V14 adds no grants.
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("update merchants.api_key set expires_at = now(), "
          + "last_used_at = now() where key_hash = 'lifecycle-hash-4'"));
      assertEquals(1, st.executeUpdate("update merchants.operator_key set expires_at = now(), "
          + "last_used_at = now() where key_hash = 'lifecycle-hash-2'"));
    }
  }
}
