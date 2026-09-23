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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The M12 review's missing pins: catalogs are disjoint per namespace,
 *  namespaces are isolated in BOTH directions, and the operator pagination
 *  controller is pinned independently of its merchant mirror. */
@AutoConfigureMockMvc
class OperatorEventCatalogTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private WebhookStore webhookStore;

  @Test
  void catalogsAreDisjointPerNamespace() throws Exception {
    // Operator surface rejects a payment type...
    mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("catalog")
                + "\",\"eventTypes\":[\"payment_intent.settled\"]}"))
        .andExpect(status().isBadRequest());
    // ...and the merchant surface rejects the conciliation type.
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + ApiDrivers.createMerchantAndGetKey(
                mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Catalog Merchant"))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("catalog")
                + "\",\"eventTypes\":[\"conciliation.report_open\"]}"))
        .andExpect(status().isBadRequest());
    // The merchant catalog is the union of intent, payout, and refund types:
    // a payout or refund type registers exactly where the operator surface
    // above rejects the payment type.
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + ApiDrivers.createMerchantAndGetKey(
                mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Payout Catalog Merchant"))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("payout-catalog")
                + "\",\"eventTypes\":[\"payout.settled\",\"payout.failed\",\"payout.expired\"]}"))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + ApiDrivers.createMerchantAndGetKey(
                mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Refund Catalog Merchant"))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("refund-catalog")
                + "\",\"eventTypes\":[\"refund.settled\",\"refund.failed\",\"refund.expired\"]}"))
        .andExpect(status().isCreated());
  }

  @Test
  void merchantSurfaceNeverSeesOperatorEndpoints() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("reverse") + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    String operatorEndpointId = com.jayway.jsonpath.JsonPath.read(
        created.getResponse().getContentAsString(), "$.publicId");
    String merchantBearer = ApiDrivers.createMerchantAndGetKey(
        mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Catalog Merchant");
    MvcResult listed = mockMvc.perform(get("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantBearer))
        .andExpect(status().isOk()).andReturn();
    org.junit.jupiter.api.Assertions.assertFalse(
        listed.getResponse().getContentAsString().contains(operatorEndpointId));
    mockMvc.perform(get("/v1/webhook-endpoints/" + operatorEndpointId)
            .header("Authorization", "Bearer " + merchantBearer))
        .andExpect(status().isNotFound());
  }

  @Test
  void operatorPaginationIsIndependentlyPinned() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("pins") + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    String endpointId = com.jayway.jsonpath.JsonPath.read(
        created.getResponse().getContentAsString(), "$.publicId");
    String auth = ApiDrivers.operatorAuth(operatorKeys);
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", auth).param("limit", "0"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", auth).param("limit", "101"))
        .andExpect(status().isBadRequest());
    webhookStore.insertEvent(UUID.randomUUID(), null, "conciliation.report_open",
        "{\"pins\":1}", Instant.now());
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", auth).param("after", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }
}
