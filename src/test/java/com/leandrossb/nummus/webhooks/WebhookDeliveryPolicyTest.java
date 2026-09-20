package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookDeliveryWorker;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delivery-time revalidation: an endpoint whose URL violates the policy is
 * inserted directly via SQL — the REST guard would have rejected it, which is
 * exactly what an attacker's DNS change bypasses. The worker must treat the
 * URL as a failed attempt, not dial it.
 */
@AutoConfigureMockMvc
class WebhookDeliveryPolicyTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private WebhookDeliveryWorker worker;
  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void policyViolatingUrlFailsTheAttemptWithoutDialing() throws Exception {
    // Merchant + endpoint inserted raw with a private URL, one due delivery.
    String endpointId = UUID.randomUUID().toString();
    String eventId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('" + endpointId + "', 'https://192.168.0.1/hook', 's1')");
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('" + eventId + "', 'probe.evt', '{}', now())");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, next_attempt_at) "
          + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
          + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), now()");
    }

    worker.deliverDue();

    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      var rs = st.executeQuery("select status, attempts from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')");
      rs.next();
      assertEquals("PENDING", rs.getString(1)); // backoff applied, not terminal on attempt 1
      assertEquals(1, rs.getInt(2));
      var backoff = st.executeQuery("select next_attempt_at > now() as later from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')");
      backoff.next();
      assertEquals(true, backoff.getBoolean(1));
    }
  }

  @Test
  void redirectResponseIsNotFollowedAndFailsTheAttempt() throws Exception {
    // A policy-legal loopback receiver answering 302 with a Location pointing
    // at a 200 target: a followed redirect would surface as the target's 2xx
    // success — the pin is the bare 302 recorded as a failed attempt.
    try (ReceiverServer receiver = new ReceiverServer()) {
      receiver.redirectTo("/redirected", receiver.url("/redirect-target"));
      String endpointId = UUID.randomUUID().toString();
      String eventId = UUID.randomUUID().toString();
      try (Connection c = adminConnection(); Statement st = c.createStatement()) {
        st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
            + "values ('" + endpointId + "', '" + receiver.url("/redirected") + "', 's1')");
        st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
            + "values ('" + eventId + "', 'probe.evt', '{}', now())");
        st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, next_attempt_at) "
            + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
            + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), now()");
      }

      worker.deliverDue();

      try (Connection c = adminConnection(); Statement st = c.createStatement()) {
        var rs = st.executeQuery("select status, attempts, last_response_status "
            + "from webhooks.webhook_delivery "
            + "where endpoint_id = (select id from webhooks.webhook_endpoint "
            + "where public_id = '" + endpointId + "')");
        rs.next();
        assertEquals("PENDING", rs.getString(1));
        assertEquals(1, rs.getInt(2));
        assertEquals(302, rs.getInt(3));
      }
    }
  }
}
