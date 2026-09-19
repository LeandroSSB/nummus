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
class OperatorKeysRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleInsertsAndRevokesOperatorKeys() throws Exception {
    String keyHash = "hash-op-" + UUID.randomUUID();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO merchants.operator_key (key_hash, prefix) VALUES ('"
          + keyHash + "', 'nummus_s')");
      st.executeUpdate("UPDATE merchants.operator_key SET status = 'REVOKED' WHERE key_hash = '"
          + keyHash + "'");
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM merchants.operator_key WHERE key_hash = '" + keyHash + "'")) {
        rs.next();
        assertEquals("REVOKED", rs.getString(1));
      }
    }
  }
}
