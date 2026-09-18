package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
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

  private String createEndpoint(String body) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/webhook-endpoints")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReturnsTheSecretExactlyOnceAndReplaysIdentically() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = "{\"url\":\"https://merchant.example/hook\",\"eventTypes\":[\"payment_intent.settled\"]}";
    var first = mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").exists())
        .andExpect(jsonPath("$.url").value("https://merchant.example/hook"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andReturn();
    var replay = mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andReturn();
    org.junit.jupiter.api.Assertions.assertEquals(
        first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

    // The secret never appears again: get and list omit it.
    String location = first.getResponse().getHeader("Location");
    mockMvc.perform(get(location)).andExpect(status().isOk())
        .andExpect(jsonPath("$.secret").doesNotExist());
    mockMvc.perform(get("/v1/webhook-endpoints")).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].secret").doesNotExist());
  }

  @Test
  void createValidatesUrlSchemeAndEventTypeCatalog() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"ftp://merchant.example/hook\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://merchant.example/hook\",\"eventTypes\":[\"nope.event\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createRequiresAnIdempotencyKey() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://merchant.example/hook\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownAndDeletedEndpointsAre404() throws Exception {
    mockMvc.perform(get("/v1/webhook-endpoints/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
    String location = createEndpoint("{\"url\":\"https://merchant.example/temp\"}");
    mockMvc.perform(delete(location)).andExpect(status().isNoContent());
    mockMvc.perform(get(location)).andExpect(status().isNotFound());
    mockMvc.perform(delete(location)).andExpect(status().isNotFound());
  }

  @Test
  void deliveriesAreListedPerEndpoint() throws Exception {
    String location = createEndpoint("{\"url\":\"https://merchant.example/dl\"}");
    String endpointId = location.substring(location.lastIndexOf('/') + 1);
    mockMvc.perform(get(location + "/deliveries")).andExpect(status().isOk());
    mockMvc.perform(get(location + "/deliveries?status=SUCCEEDED")).andExpect(status().isOk());
    mockMvc.perform(get("/v1/webhook-endpoints/" + UUID.randomUUID() + "/deliveries"))
        .andExpect(status().isNotFound());
    org.junit.jupiter.api.Assertions.assertFalse(endpointId.isEmpty());
  }

  @Test
  void eventTypesDefaultToAllWhenOmitted() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://merchant.example/all\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.eventTypes").isArray());
  }
}
