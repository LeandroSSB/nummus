package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class WebhookDeliveryIdSchemaTest extends IntegrationTestBase {

  @Test
  void deliveriesCarryAGloballyUniqueBackfilledPublicId() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      // Two endpoints + one event + two deliveries, inserted raw — the column
      // must backfill and stay unique without any insert-side cooperation.
      // (V8 keeps one delivery row per event/endpoint pair, so the second
      // delivery carries its own endpoint.)
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('33333333-3333-4333-8333-333333333331', 'http://127.0.0.1/hook', 's1')");
      long endpointId = queryId(st, "select id from webhooks.webhook_endpoint "
          + "where public_id = '33333333-3333-4333-8333-333333333331'");
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('33333333-3333-4333-8333-333333333334', 'http://127.0.0.1/hook', 's1')");
      long secondEndpointId = queryId(st, "select id from webhooks.webhook_endpoint "
          + "where public_id = '33333333-3333-4333-8333-333333333334'");
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('33333333-3333-4333-8333-333333333332', 'probe.evt', '{}', now())");
      long eventId = queryId(st, "select id from webhooks.webhook_event "
          + "where public_id = '33333333-3333-4333-8333-333333333332'");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id) "
          + "values (" + eventId + ", " + endpointId + ")");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id) "
          + "values (" + eventId + ", " + secondEndpointId + ")");

      var rs = st.executeQuery("select public_id from webhooks.webhook_delivery "
          + "where event_id = " + eventId);
      assertTrue(rs.next());
      String first = rs.getString(1);
      assertTrue(rs.next());
      assertNotEquals(first, rs.getString(1));

      SQLException duplicate = assertThrows(SQLException.class, () -> st.executeUpdate(
          "update webhooks.webhook_delivery set public_id = '" + first
              + "' where event_id = " + eventId
              + " and public_id <> '" + first + "'"));
      assertEquals("23505", duplicate.getSQLState());
    }
  }

  private long queryId(Statement st, String sql) throws SQLException {
    var rs = st.executeQuery(sql);
    rs.next();
    return rs.getLong(1);
  }
}
