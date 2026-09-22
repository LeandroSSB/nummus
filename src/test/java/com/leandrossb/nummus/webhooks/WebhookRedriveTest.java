package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WebhookRedriveTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private OperatorKeysService operatorKeys;

  private String[] merchantWithTerminalDelivery() throws Exception {
    // Two merchants: owner and stranger. Owner gets a FAILED delivery.
    String[] owner = newMerchant();   // {key, merchantId}
    String stranger = newMerchant()[0];
    String endpointId = UUID.randomUUID().toString();
    String eventId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, merchant_public_id, url, secret) "
          + "values ('" + endpointId + "', '" + owner[1] + "', 'http://127.0.0.1:9/hook', 's1')");
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('" + eventId + "', 'probe.evt', '{}', now())");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, status, attempts, next_attempt_at, last_attempt_at) "
          + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
          + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), "
          + "'FAILED', 8, now(), now()");
    }
    String deliveryId;
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      var rs = st.executeQuery("select public_id from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')");
      rs.next();
      deliveryId = rs.getString(1);
    }
    return new String[] {owner[0], stranger, deliveryId, endpointId};
  }

  private String[] newMerchant() throws Exception {
    var created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create("probe", null).secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"redrive probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    return new String[] {
        com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret"),
        com.jayway.jsonpath.JsonPath.read(body, "$.merchantId")};
  }

  @Test
  void redriveRequeuesAFailedDeliveryWithAFreshCycle() throws Exception {
    String[] f = merchantWithTerminalDelivery();
    String idem = UUID.randomUUID().toString();
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, idem))
        .andExpect(status().isAccepted());
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      var rs = st.executeQuery("select status, attempts, next_attempt_at <= now() as due "
          + "from webhooks.webhook_delivery where public_id = '" + f[2] + "'");
      rs.next();
      org.junit.jupiter.api.Assertions.assertEquals("PENDING", rs.getString(1));
      org.junit.jupiter.api.Assertions.assertEquals(0, rs.getInt(2));
      org.junit.jupiter.api.Assertions.assertTrue(rs.getBoolean(3));
    }
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, idem))
        .andExpect(status().isAccepted()); // idempotent replay of the stored 202
    // The listing exposes the delivery's public id — the redrive address.
    mockMvc.perform(get("/v1/webhook-endpoints/" + f[3] + "/deliveries")
            .header("Authorization", "Bearer " + f[0]))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].deliveryId").exists());
  }

  @Test
  void foreignMerchantAndNonFailedDeliveriesAreNotFound() throws Exception {
    String[] f = merchantWithTerminalDelivery();
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[1]).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
    // Non-FAILED: requeue the owner's delivery first, then a second redrive 404s.
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isAccepted());
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isNotFound()); // PENDING now — not FAILED
    mockMvc.perform(post("/v1/webhook-deliveries/" + UUID.randomUUID() + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isNotFound()); // unknown
  }

  @Test
  void keylessRedriveIsUnauthorizedBeforeIdempotency() throws Exception {
    String[] f = merchantWithTerminalDelivery();
    // No Authorization and no Idempotency-Key: the auth filter's 401 must
    // win over the idempotency filter's 400 — the route is merchant-protected.
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("WWW-Authenticate"));
  }
}
