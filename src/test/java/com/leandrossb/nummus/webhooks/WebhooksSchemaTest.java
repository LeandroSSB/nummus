package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhooksSchemaTest extends IntegrationTestBase {

  @Test
  void schemaEnforcesUrlCheckDeliveryUniquenessAndDefaults() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      // Bad URL rejected by the check constraint.
      SQLException badUrl = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO webhooks.webhook_endpoint (url, secret) VALUES ('ftp://nope', 's')"));
      assertEquals("23514", badUrl.getSQLState());

      String endpointId = UUID.randomUUID().toString();
      st.executeUpdate("INSERT INTO webhooks.webhook_endpoint (public_id, url, secret) VALUES ('"
          + endpointId + "', 'https://merchant.example/hook', 'whsec_x')");
      try (ResultSet rs = st.executeQuery(
          "SELECT event_types::text, status FROM webhooks.webhook_endpoint WHERE public_id = '" + endpointId + "'")) {
        assertTrue(rs.next());
        assertEquals("[]", rs.getString(1).trim());
        assertEquals("ACTIVE", rs.getString(2));
      }

      String eventId = UUID.randomUUID().toString();
      st.executeUpdate("INSERT INTO webhooks.webhook_event (public_id, type, payload, occurred_at) VALUES ('"
          + eventId + "', 'payment_intent.settled', '{}', now())");
      long deliveryId;
      try (ResultSet rs = st.executeQuery(
          "SELECT id FROM webhooks.webhook_event WHERE public_id = '" + eventId + "'")) {
        rs.next();
        deliveryId = rs.getLong(1);
      }
      long endpointRowId;
      try (ResultSet rs = st.executeQuery(
          "SELECT id FROM webhooks.webhook_endpoint WHERE public_id = '" + endpointId + "'")) {
        rs.next();
        endpointRowId = rs.getLong(1);
      }
      st.executeUpdate("INSERT INTO webhooks.webhook_delivery (event_id, endpoint_id) VALUES ("
          + deliveryId + ", " + endpointRowId + ")");
      SQLException duplicate = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO webhooks.webhook_delivery (event_id, endpoint_id) VALUES ("
              + deliveryId + ", " + endpointRowId + ")"));
      assertEquals("23505", duplicate.getSQLState());
    }
  }
}
