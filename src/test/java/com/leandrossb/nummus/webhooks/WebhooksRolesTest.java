package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class WebhooksRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleCanRunTheFullDeliveryLifecycle() throws Exception {
    String key = "roles-" + UUID.randomUUID();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO webhooks.webhook_endpoint (public_id, url, secret) VALUES ('"
          + UUID.randomUUID() + "', 'https://merchant.example/" + key + "', 'whsec_role')");
      st.executeUpdate("INSERT INTO webhooks.webhook_event (public_id, type, payload, occurred_at) VALUES ('"
          + UUID.randomUUID() + "', 'payment_intent.settled', '{}', now())");
      st.executeUpdate("""
          INSERT INTO webhooks.webhook_delivery (event_id, endpoint_id)
          SELECT e.id, p.id FROM webhooks.webhook_event e, webhooks.webhook_endpoint p
          WHERE p.url LIKE '%/""" + key + "'");
      st.executeUpdate("""
          UPDATE webhooks.webhook_delivery SET status = 'SUCCEEDED', attempts = 1,
            last_attempt_at = now(), last_response_status = 200
          WHERE endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/""" + key + "')");
      try (ResultSet rs = st.executeQuery("""
          SELECT d.status, d.attempts, d.last_response_status FROM webhooks.webhook_delivery d
          WHERE d.endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/""" + key + "')")) {
        assertTrue(rs.next());
        assertEquals("SUCCEEDED", rs.getString(1));
        assertEquals(1, rs.getInt(2));
        assertEquals(200, rs.getInt(3));
      }
    }
  }
}
