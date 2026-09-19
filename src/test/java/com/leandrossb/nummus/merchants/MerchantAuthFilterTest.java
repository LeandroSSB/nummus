package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class MerchantAuthFilterTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth;

  /** One operator key per test — the merchants surface is operator-gated. */
  private String operatorAuth() {
    if (operatorAuth == null) {
      operatorAuth = "Bearer " + operatorKeys.create().secret();
    }
    return operatorAuth;
  }

  @Test
  void merchantRoutesRequireAValidBearerKey() throws Exception {
    mockMvc.perform(get("/v1/me")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer garbage"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer nummus_sk_unknown-key-unknown-key-unknown-key"))
        .andExpect(status().isUnauthorized());
    // Operator routes require an operator key: keyless is 401, and a real
    // merchant reads 200 behind it.
    String merchantId = createMerchantAsOperator();
    mockMvc.perform(get("/v1/merchants/" + merchantId))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/merchants/" + merchantId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk());
  }

  @Test
  void authenticationPrecedesIdempotencyValidation() throws Exception {
    // Neither an API key nor an Idempotency-Key: 401 (auth), not 400 (idempotency).
    mockMvc.perform(post("/v1/me/api-keys")
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  private String createMerchantAsOperator() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Filter Probe Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId");
  }
}
