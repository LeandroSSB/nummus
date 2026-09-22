package com.leandrossb.nummus.merchants;

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
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class SubMillisecondExpiryTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private String createMerchantAndGetKey() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"SubMs Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  @Test
  void subMillisecondExpiryIsRejected() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey();
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT0.0005S\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void oneMillisecondExpiryMintsNormally() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey();
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT0.001S\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }
}
