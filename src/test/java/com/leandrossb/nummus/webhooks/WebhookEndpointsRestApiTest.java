package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class WebhookEndpointsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String merchantKey;

  /** Fresh merchant per test: every endpoint this class touches belongs to it.
   *  Creation is operator-gated — one operator key per fixture mint. */
  @BeforeEach
  void createMerchantFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create().secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Webhooks Fixture Merchant\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    merchantKey = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  private String createEndpoint(String body) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReturnsTheSecretExactlyOnceAndReplaysIdentically() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = "{\"url\":\"https://example.com/hook\",\"eventTypes\":[\"payment_intent.settled\"]}";
    var first = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey).header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").exists())
        .andExpect(jsonPath("$.url").value("https://example.com/hook"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andReturn();
    var replay = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey).header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andReturn();
    org.junit.jupiter.api.Assertions.assertEquals(
        first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

    // The secret never appears again: get and list omit it.
    String location = first.getResponse().getHeader("Location");
    mockMvc.perform(get(location).header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secret").doesNotExist());
    mockMvc.perform(get("/v1/webhook-endpoints").header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].secret").doesNotExist());
  }

  @Test
  void createValidatesUrlSchemeAndEventTypeCatalog() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"ftp://example.com/hook\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://example.com/hook\",\"eventTypes\":[\"nope.event\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createRequiresAnIdempotencyKey() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://example.com/hook\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownAndDeletedEndpointsAre404() throws Exception {
    mockMvc.perform(get("/v1/webhook-endpoints/" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isNotFound());
    String location = createEndpoint("{\"url\":\"https://example.com/temp\"}");
    mockMvc.perform(delete(location).header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isNoContent());
    mockMvc.perform(get(location).header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete(location).header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isNotFound());
  }

  @Test
  void deliveriesAreListedPerEndpoint() throws Exception {
    String location = createEndpoint("{\"url\":\"https://example.com/dl\"}");
    String endpointId = location.substring(location.lastIndexOf('/') + 1);
    mockMvc.perform(get(location + "/deliveries").header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk());
    mockMvc.perform(get(location + "/deliveries?status=SUCCEEDED")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/webhook-endpoints/" + UUID.randomUUID() + "/deliveries")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isNotFound());
    org.junit.jupiter.api.Assertions.assertFalse(endpointId.isEmpty());
  }

  @Test
  void eventTypesDefaultToAllWhenOmitted() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://example.com/all\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.eventTypes").isArray());
  }
}
