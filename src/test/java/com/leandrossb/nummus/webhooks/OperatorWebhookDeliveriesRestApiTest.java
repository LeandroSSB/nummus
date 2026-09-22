package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Operator deliveries over REST: keyset pagination with {@code Next-Cursor}
 * and FAILED redrive, mirroring the merchant surface exactly — but scoped to
 * the NULL audience, so a merchant endpoint is invisible here (404) in both
 * directions. Deliveries are seeded through the store's operator fan-out
 * (Task 2), never over HTTP.
 */
@AutoConfigureMockMvc
class OperatorWebhookDeliveriesRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private WebhookStore webhookStore;

  private String registerOperatorEndpoint() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("deliveries") + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.publicId");
  }

  /** Fans out through the store's operator namespace (NULL audience): one
   *  PENDING delivery per ACTIVE operator endpoint per event. */
  private void seedEvents(int count) {
    for (int i = 0; i < count; i++) {
      webhookStore.insertEvent(UUID.randomUUID(), null, "conciliation.report_open",
          "{\"probe\":" + i + "}", Instant.now());
    }
  }

  @Test
  void deliveriesPaginateWithNextCursor() throws Exception {
    String endpointId = registerOperatorEndpoint();
    seedEvents(3);
    MvcResult page1 = mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(header().exists("Next-Cursor"))
        .andReturn();
    String cursor = page1.getResponse().getHeader("Next-Cursor");
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).param("limit", "2").param("after", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }

  @Test
  void redriveRequeuesAFailedDelivery() throws Exception {
    String endpointId = registerOperatorEndpoint();
    seedEvents(1);
    MvcResult listed = mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)))
        .andExpect(status().isOk()).andReturn();
    String deliveryId = com.jayway.jsonpath.JsonPath.read(
        listed.getResponse().getContentAsString(), "$[0].deliveryId");
    // Force terminal FAILED so redrive has something to requeue.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update webhooks.webhook_delivery set status = 'FAILED', attempts = 8 "
          + "where public_id = '" + deliveryId + "'");
    }
    mockMvc.perform(post("/v1/operator/webhook-deliveries/" + deliveryId + "/redrive")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isAccepted());
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).param("status", "PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].deliveryId").value(deliveryId))
        .andExpect(jsonPath("$[0].attempts").value(0));
  }

  @Test
  void operatorRoutesNeverTouchMerchantDeliveries() throws Exception {
    // A merchant endpoint + delivery...
    String bearer = ApiDrivers.createMerchantAndGetKey(
        mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Parity Merchant");
    MvcResult endpoint = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("m-hook") + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantEndpointId = com.jayway.jsonpath.JsonPath.read(
        endpoint.getResponse().getContentAsString(), "$.publicId");
    // ...is invisible on the operator surface: listing 404s, redrive of any
    // delivery under it is unreachable because the listing never yields ids.
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + merchantEndpointId + "/deliveries")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)))
        .andExpect(status().isNotFound());
  }
}
