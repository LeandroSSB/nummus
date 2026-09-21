package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookDeliveryDeleteRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleDeletesDeliveries() throws Exception {
    String endpointId = UUID.randomUUID().toString();
    String eventId = UUID.randomUUID().toString();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('" + endpointId + "', 'http://127.0.0.1/hook', 's1')");
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('" + eventId + "', 'probe.evt', '{}', now())");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id) "
          + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
          + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "')");
      assertEquals(1, st.executeUpdate("delete from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')"));
      assertTrue(st.executeQuery("select 1 from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')").next() == false);
    }
  }
}
