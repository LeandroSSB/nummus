package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

// PER_CLASS makes JUnit create the test instance before @BeforeAll, so the
// Spring context (and Flyway) is up when enableAppRoleLogin runs; otherwise
// an isolated run of this class alone would execute @BeforeAll before any
// migration is applied and fail with a missing role.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerRolesTest extends IntegrationTestBase {

  @BeforeAll
  static void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleCanReadInsertAndColumnUpdateAccounts() throws Exception {
    String accountPublicId = UUID.randomUUID().toString();
    try (Connection app = appConnection(); Statement st = app.createStatement()) {
      st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
          + accountPublicId + "', 'role test', 'LIABILITY')");
      st.executeUpdate("UPDATE ledger.ledger_account SET status = 'FROZEN' WHERE public_id = '"
          + accountPublicId + "'");
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM ledger.ledger_account WHERE public_id = '" + accountPublicId + "'")) {
        assertTrue(rs.next());
        assertTrue("FROZEN".equals(rs.getString(1)));
      }
    }
  }

  @Test
  void appRoleCannotMutateJournalOrOtherAccountColumns() throws Exception {
    String accountPublicId = UUID.randomUUID().toString();
    try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
      st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
          + accountPublicId + "', 'denied test', 'ASSET')");
    }
    try (Connection app = appConnection(); Statement st = app.createStatement()) {
      assertDenied(st, "UPDATE ledger.journal_posting SET amount = 1.0000");
      assertDenied(st, "DELETE FROM ledger.journal_transaction");
      assertDenied(st, "UPDATE ledger.ledger_account SET name = 'renamed' WHERE public_id = '"
          + accountPublicId + "'");
      assertDenied(st, "DELETE FROM ledger.ledger_account WHERE public_id = '" + accountPublicId + "'");
    }
  }

  @Test
  void appRoleCanCommitABalancedTransaction() throws Exception {
    String assetId = UUID.randomUUID().toString();
    String liabilityId = UUID.randomUUID().toString();
    try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
      st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
          + assetId + "', 'app asset', 'ASSET')");
      st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
          + liabilityId + "', 'app liability', 'LIABILITY')");
    }
    assertDoesNotThrow(() -> {
      try (Connection app = appConnection()) {
        app.setAutoCommit(false);
        String txId = UUID.randomUUID().toString();
        try (Statement st = app.createStatement()) {
          st.executeUpdate("INSERT INTO ledger.journal_transaction (public_id, memo) VALUES ('"
              + txId + "', 'app role post')");
          st.executeUpdate("INSERT INTO ledger.journal_posting (transaction_id, account_id, direction, amount) "
              + "SELECT t.id, a.id, 'DEBIT', 7.5000 FROM ledger.journal_transaction t, ledger.ledger_account a "
              + "WHERE t.public_id = '" + txId + "' AND a.public_id = '" + assetId + "'");
          st.executeUpdate("INSERT INTO ledger.journal_posting (transaction_id, account_id, direction, amount) "
              + "SELECT t.id, a.id, 'CREDIT', 7.5000 FROM ledger.journal_transaction t, ledger.ledger_account a "
              + "WHERE t.public_id = '" + txId + "' AND a.public_id = '" + liabilityId + "'");
        }
        app.commit();
      }
    });
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
