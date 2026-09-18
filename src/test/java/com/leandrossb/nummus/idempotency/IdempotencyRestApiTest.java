package com.leandrossb.nummus.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class IdempotencyRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  private static final String KEY = "Idempotency-Key";

  @Test
  void missingOrMalformedKeyIsRejectedOnEveryMerchantPost() throws Exception {
    // No header at all.
    mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"No Key Merchant\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
    // Blank header.
    mockMvc.perform(post("/v1/accounts").header(KEY, "   ")
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Blank Key Merchant\"}"))
        .andExpect(status().isBadRequest());
    // Over 255 characters.
    mockMvc.perform(post("/v1/payment-intents").header(KEY, "k".repeat(256))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":1.0000}"))
        .andExpect(status().isBadRequest());
    // The transition endpoints are merchant writes too: a real account + no key.
    String accountId = mockMvc.perform(post("/v1/accounts").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Valid Merchant\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getHeader("Location");
    mockMvc.perform(post(accountId + "/freeze")).andExpect(status().isBadRequest());
    mockMvc.perform(post(accountId + "/unfreeze")).andExpect(status().isBadRequest());
    mockMvc.perform(post(accountId + "/close")).andExpect(status().isBadRequest());
  }

  @Test
  void simulatorEndpointsDoNotRequireAKey() throws Exception {
    mockMvc.perform(post("/simulator/charges/" + UUID.randomUUID() + "/pay"))
        .andExpect(status().isNotFound()); // routed, key-free: 404 from the domain, not 400 from the filter
  }
}
