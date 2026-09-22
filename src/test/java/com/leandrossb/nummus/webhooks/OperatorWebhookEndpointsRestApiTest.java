package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The operator webhook namespace over REST: registration validates against
 * the conciliation catalog, listing/getting/deleting see only the NULL
 * audience (the operator namespace) — merchant endpoints are invisible in
 * both directions, and the M10 URL policy still applies.
 */
@AutoConfigureMockMvc
class OperatorWebhookEndpointsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String registerOperatorEndpoint(String url, String typesJson) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + url + "\",\"eventTypes\":" + typesJson + "}"))
        .andExpect(status().isCreated()).andReturn();
    return created.getResponse().getContentAsString();
  }

  @Test
  void registerCreatesWithSecretOnceAndPolicyRejectsUnsafeUrls() throws Exception {
    String body = registerOperatorEndpoint(ApiDrivers.loopbackUrl("hook"), "[\"conciliation.report_open\"]");
    org.junit.jupiter.api.Assertions.assertTrue(
        com.jayway.jsonpath.JsonPath.read(body, "$.secret").toString().startsWith("whsec_"));
    String endpointId = com.jayway.jsonpath.JsonPath.read(body, "$.publicId");
    // The secret never comes back: listing carries ids and prefixes only.
    MvcResult listed = mockMvc.perform(get("/v1/operator/webhook-endpoints")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)))
        .andExpect(status().isOk()).andReturn();
    org.junit.jupiter.api.Assertions.assertTrue(
        listed.getResponse().getContentAsString().contains(endpointId));
    org.junit.jupiter.api.Assertions.assertFalse(
        listed.getResponse().getContentAsString().contains("whsec_"));
    // The M10 URL policy applies to the operator namespace too.
    mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://192.168.0.9/hook\",\"eventTypes\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownEventTypesAreRejected() throws Exception {
    mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("bad") + "\",\"eventTypes\":[\"made.up.event\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void namespacesAreIsolatedInBothDirections() throws Exception {
    // Operator listing never shows merchant endpoints...
    String operatorAuth = ApiDrivers.operatorAuth(operatorKeys);
    String merchantBearer = "Bearer " + ApiDrivers.createMerchantAndGetKey(
        mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Namespace Merchant");
    MvcResult merchantEndpoint = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", merchantBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("m-hook") + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantEndpointId = com.jayway.jsonpath.JsonPath.read(
        merchantEndpoint.getResponse().getContentAsString(), "$.publicId");
    MvcResult operatorList = mockMvc.perform(get("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth))
        .andExpect(status().isOk()).andReturn();
    org.junit.jupiter.api.Assertions.assertFalse(
        operatorList.getResponse().getContentAsString().contains(merchantEndpointId));
    // ...and the operator route 404s a merchant endpoint id.
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + merchantEndpointId)
            .header("Authorization", operatorAuth))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteIsSoftAndThenUnknown() throws Exception {
    String body = registerOperatorEndpoint(ApiDrivers.loopbackUrl("gone"), "[]");
    String endpointId = com.jayway.jsonpath.JsonPath.read(body, "$.publicId");
    mockMvc.perform(delete("/v1/operator/webhook-endpoints/" + endpointId)
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId)
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)))
        .andExpect(status().isNotFound());
  }
}
