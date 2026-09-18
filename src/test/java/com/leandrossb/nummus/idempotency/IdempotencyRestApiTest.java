package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

  @Test
  void retryReplaysTheStoredResponseVerbatimWithoutReExecuting() throws Exception {
    String accountId = mockMvc.perform(post("/v1/accounts").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Replay Merchant\"}"))
        .andReturn().getResponse().getHeader("Location");
    String body = "{\"accountId\":\"" + accountId.substring(accountId.lastIndexOf('/') + 1) + "\",\"amount\":12.5000}";
    String key = UUID.randomUUID().toString();

    var first = mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();
    var retry = mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();

    assertEquals(first.getResponse().getContentAsString(), retry.getResponse().getContentAsString());
    assertEquals(first.getResponse().getHeader("Location"), retry.getResponse().getHeader("Location"));
    assertTrue(first.getResponse().getHeader("Idempotency-Replayed") == null);
    assertEquals("true", retry.getResponse().getHeader("Idempotency-Replayed"));

    // No double execution: exactly one intent exists for this account.
    try (var c = adminConnection(); var st = c.createStatement()) {
      try (var rs = st.executeQuery(
          "SELECT count(*) FROM payments.payment_intent WHERE account_public_id = '"
              + accountId.substring(accountId.lastIndexOf('/') + 1) + "'")) {
        rs.next();
        assertEquals(1, rs.getInt(1));
      }
    }
  }

  @Test
  void sameKeyWithADifferentRequestIsRejected() throws Exception {
    String key = UUID.randomUUID().toString();
    mockMvc.perform(post("/v1/accounts").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"First Op\"}"))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/accounts").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Second Op\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422));
  }

  @Test
  void accountTransitionsReplayTheirStored200() throws Exception {
    String key = UUID.randomUUID().toString();
    String location = mockMvc.perform(post("/v1/accounts").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Freeze Replay\"}"))
        .andReturn().getResponse().getHeader("Location");
    var first = mockMvc.perform(post(location + "/freeze").header(KEY, key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"))
        .andReturn();
    // Re-freezing for real would be a 409 (account not ACTIVE) — the replay
    // must return the stored 200 instead.
    var retry = mockMvc.perform(post(location + "/freeze").header(KEY, key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"))
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andReturn();

    assertEquals(first.getResponse().getContentAsString(), retry.getResponse().getContentAsString());
  }

  @Test
  void domainErrorsAreNotStoredAndRerunDeterministically() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = "{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":5.0000}";
    mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNotFound()) // re-executed, same deterministic 404
        .andExpect(header().doesNotExist("Idempotency-Replayed"));
  }
}
