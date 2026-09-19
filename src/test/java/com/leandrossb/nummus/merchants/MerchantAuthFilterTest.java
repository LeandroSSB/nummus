package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class MerchantAuthFilterTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void merchantRoutesRequireAValidBearerKey() throws Exception {
    mockMvc.perform(get("/v1/me")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer garbage"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer nummus_sk_unknown-key-unknown-key-unknown-key"))
        .andExpect(status().isUnauthorized());
    // Operator routes never require auth.
    mockMvc.perform(get("/v1/merchants/" + java.util.UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void authenticationPrecedesIdempotencyValidation() throws Exception {
    // Neither an API key nor an Idempotency-Key: 401 (auth), not 400 (idempotency).
    mockMvc.perform(post("/v1/me/api-keys")
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }
}
