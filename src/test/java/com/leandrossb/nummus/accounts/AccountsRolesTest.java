package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * PER_CLASS so the Spring context (and Flyway's V4) is up before the
 * non-static @BeforeAll grants the app role a login — same reason as
 * LedgerRolesTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountsRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleCanInsertReadAndUpdateStatusButNotMutateHolderData() throws Exception {
    String publicId = UUID.randomUUID().toString();
    try (Connection app = appConnection(); Statement st = app.createStatement()) {
      st.executeUpdate("INSERT INTO accounts.payment_account (public_id, holder_name, ledger_account_public_id) "
          + "VALUES ('" + publicId + "', 'roles test', '" + UUID.randomUUID() + "')");
      st.executeUpdate("UPDATE accounts.payment_account SET status = 'FROZEN' WHERE public_id = '" + publicId + "'");
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM accounts.payment_account WHERE public_id = '" + publicId + "'")) {
        assertTrue(rs.next());
        assertTrue("FROZEN".equals(rs.getString(1)));
      }
      assertDenied(st, "UPDATE accounts.payment_account SET holder_name = 'renamed' WHERE public_id = '" + publicId + "'");
      assertDenied(st, "UPDATE accounts.payment_account SET ledger_account_public_id = '" + UUID.randomUUID()
          + "' WHERE public_id = '" + publicId + "'");
      assertDenied(st, "DELETE FROM accounts.payment_account WHERE public_id = '" + publicId + "'");
    }
  }

  private void assertDenied(Statement st, String sql) throws SQLException {
    try {
      st.executeUpdate(sql);
      fail("expected permission denial for: " + sql);
    } catch (SQLException e) {
      assertTrue(e.getMessage().contains("permission denied"),
          "expected 'permission denied' but got: " + e.getMessage());
    }
  }
}
