package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentsSchemaTest extends IntegrationTestBase {

  @Test
  void schemaAcceptsIntentRowsAndSeedsTheClearingAccount() throws Exception {
    String intentId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO payments.payment_intent (public_id, account_public_id, amount, charge_public_id, expires_at) "
          + "VALUES ('" + intentId + "', '" + UUID.randomUUID() + "', 10.0000, '" + UUID.randomUUID() + "', now() + interval '30 minutes')");
      try (ResultSet rs = st.executeQuery(
          "SELECT status, journal_transaction_public_id FROM payments.payment_intent WHERE public_id = '" + intentId + "'")) {
        assertTrue(rs.next());
        assertEquals("CREATED", rs.getString(1));
        assertTrue(rs.getObject(2) == null);
      }
      try (ResultSet rs = st.executeQuery(
          "SELECT type, currency, status FROM ledger.ledger_account WHERE public_id = '5f9c3b2e-0000-4000-8000-000000000001'")) {
        assertTrue(rs.next());
        assertEquals("ASSET", rs.getString(1));
        assertEquals("BRL", rs.getString(2).trim());
        assertEquals("ACTIVE", rs.getString(3));
      }
    }
  }
}
