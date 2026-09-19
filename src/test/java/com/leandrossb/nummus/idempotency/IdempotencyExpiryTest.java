package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class IdempotencyExpiryTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ApiKeysService apiKeys;

  private String seedMerchantKey;

  /** Accounts routes are merchant routes now; payments still act as the seed
   *  merchant (Task 5), so this class authenticates as a minted seed key. */
  @BeforeEach
  void mintSeedMerchantKey() {
    seedMerchantKey = apiKeys.create(SeedMerchant.PUBLIC_ID).secret();
  }

  @Test
  void expiredKeyIsReclaimedAndReexecutedAsNew() throws Exception {
    String accountId = JsonPath.read(mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Expiry Merchant\"}"))
        .andReturn().getResponse().getContentAsString(), "$.publicId");
    String body = "{\"accountId\":\"" + accountId + "\",\"amount\":7.0000}";
    String key = UUID.randomUUID().toString();

    MvcResult first = mockMvc.perform(post("/v1/payment-intents").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();

    // Age the stored row past its expiry DB-side (JVM/DB clocks differ; the
    // reclaim predicate evaluates expires_at against the database clock).
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE idempotency.idempotency_keys SET expires_at = now() - interval '1 minute' WHERE key = '" + key + "'");
    }

    MvcResult second = mockMvc.perform(post("/v1/payment-intents").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();

    // Reclaimed slot → new execution → a different intent, and no replay header.
    assertNull(second.getResponse().getHeader("Idempotency-Replayed"));
    String firstPublicId = JsonPath.read(first.getResponse().getContentAsString(), "$.publicId");
    String secondPublicId = JsonPath.read(second.getResponse().getContentAsString(), "$.publicId");
    assertNotEquals(firstPublicId, secondPublicId);
  }
}
