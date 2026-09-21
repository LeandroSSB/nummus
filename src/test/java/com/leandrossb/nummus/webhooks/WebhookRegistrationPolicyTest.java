package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WebhookRegistrationPolicyTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private OperatorKeysService operatorKeys;

  private String merchantKey() throws Exception {
    var created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create(null).secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"policy probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  @Test
  void registeringAPrivateTargetIsBadRequest() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://10.1.2.3/hook\",\"eventTypes\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void registeringALoopbackHttpTargetIsAccepted() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/hook\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated());
  }
}
