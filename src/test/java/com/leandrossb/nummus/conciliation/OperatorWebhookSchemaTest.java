package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
class OperatorWebhookSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void endpointMerchantColumnAcceptsNullAndHasNoDefault() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      // NULL-merchant insert succeeds and stays NULL (operator namespace),
      // and no seed default fires.
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret, event_types, "
          + "merchant_public_id) values ('" + UUID.randomUUID() + "', 'https://ops.example/null-probe', "
          + "'whsec_probe', '[]'::jsonb, null)");
      try (ResultSet rs = st.executeQuery("select merchant_public_id from webhooks.webhook_endpoint "
          + "where url = 'https://ops.example/null-probe'")) {
        assertTrue(rs.next());
        assertEquals(null, rs.getObject("merchant_public_id"));
      }
      // Omitting the column entirely is also accepted (no default, not NOT NULL).
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret, event_types) "
          + "values ('" + UUID.randomUUID() + "', 'https://ops.example/omitted-probe', "
          + "'whsec_probe', '[]'::jsonb)");
      try (ResultSet rs = st.executeQuery("select merchant_public_id from webhooks.webhook_endpoint "
          + "where url = 'https://ops.example/omitted-probe'")) {
        assertTrue(rs.next());
        assertEquals(null, rs.getObject("merchant_public_id"));
      }
    }
  }

  @Test
  void ingestStateIsASingleSeededRowTheAppRoleCanAdvance() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("select last_window_end, updated_at "
          + "from conciliation.ingest_state where id = 1")) {
        assertTrue(rs.next());
        assertNotNull(rs.getObject("last_window_end"));
        assertNotNull(rs.getObject("updated_at"));
      }
      // Exactly one row: the check constraint rejects a second.
      assertThrows(java.sql.SQLException.class, () -> st.executeUpdate(
          "insert into conciliation.ingest_state (id, last_window_end) values (2, now())"));
    }
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("update conciliation.ingest_state "
          + "set last_window_end = now(), updated_at = now() where id = 1"));
    }
  }
}
