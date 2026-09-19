package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantsRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleRunsTheMerchantAndKeyLifecycle() throws Exception {
    String keyHash = "hash-" + UUID.randomUUID();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO merchants.merchant (public_id, name) VALUES ('"
          + UUID.randomUUID() + "', 'Roles Merchant')");
      st.executeUpdate("""
          INSERT INTO merchants.api_key (merchant_id, key_hash, prefix)
          SELECT id, '%s', 'nummus_s' FROM merchants.merchant WHERE name = 'Roles Merchant'
          """.formatted(keyHash));
      st.executeUpdate("""
          UPDATE merchants.api_key SET status = 'REVOKED' WHERE key_hash = '%s'
          """.formatted(keyHash));
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM merchants.api_key WHERE key_hash = '" + keyHash + "'")) {
        rs.next();
        assertEquals("REVOKED", rs.getString(1));
      }
    }
  }
}
