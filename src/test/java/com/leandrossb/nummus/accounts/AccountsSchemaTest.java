package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsSchemaTest extends IntegrationTestBase {

  @Test
  void schemaAcceptsPaymentAccountRowsWithDefaults() throws Exception {
    String publicId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO accounts.payment_account (public_id, holder_name, ledger_account_public_id) "
          + "VALUES ('" + publicId + "', 'schema test', '" + UUID.randomUUID() + "')");
      try (ResultSet rs = st.executeQuery(
          "SELECT status, opened_at, closed_at FROM accounts.payment_account WHERE public_id = '" + publicId + "'")) {
        assertTrue(rs.next());
        assertEquals("ACTIVE", rs.getString(1));
        assertTrue(rs.getTimestamp(2) != null);
        assertTrue(rs.getTimestamp(3) == null);
      }
    }
  }
}
