package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class IdempotencyRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ApiKeysService apiKeys;

  private static final String KEY = "Idempotency-Key";

  private String seedMerchantKey;

  /** Accounts routes are merchant routes now; payments still act as the seed
   *  merchant (Task 5), so this class authenticates as a minted seed key. */
  @BeforeEach
  void mintSeedMerchantKey() {
    seedMerchantKey = apiKeys.create(SeedMerchant.PUBLIC_ID).secret();
  }

  @Test
  void missingOrMalformedKeyIsRejectedOnEveryMerchantPost() throws Exception {
    // No header at all.
    mockMvc.perform(post("/v1/accounts").header("Authorization", "Bearer " + seedMerchantKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"No Key Merchant\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
    // Blank header.
    mockMvc.perform(post("/v1/accounts").header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, "   ")
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Blank Key Merchant\"}"))
        .andExpect(status().isBadRequest());
    // Over 255 characters.
    mockMvc.perform(post("/v1/payment-intents").header(KEY, "k".repeat(256))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":1.0000}"))
        .andExpect(status().isBadRequest());
    // The transition endpoints are merchant writes too: a real account + no key.
    String accountId = mockMvc.perform(post("/v1/accounts").header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Valid Merchant\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getHeader("Location");
    mockMvc.perform(post(accountId + "/freeze").header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post(accountId + "/unfreeze").header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post(accountId + "/close").header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isBadRequest());
  }

  @Test
  void simulatorEndpointsDoNotRequireAKey() throws Exception {
    mockMvc.perform(post("/simulator/charges/" + UUID.randomUUID() + "/pay"))
        .andExpect(status().isNotFound()); // routed, key-free: 404 from the domain, not 400 from the filter
  }

  @Test
  void retryReplaysTheStoredResponseVerbatimWithoutReExecuting() throws Exception {
    String accountId = mockMvc.perform(post("/v1/accounts").header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
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
    mockMvc.perform(post("/v1/accounts").header("Authorization", "Bearer " + seedMerchantKey).header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"First Op\"}"))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/accounts").header("Authorization", "Bearer " + seedMerchantKey).header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Second Op\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422));
  }

  @Test
  void accountTransitionsReplayTheirStored200() throws Exception {
    String key = UUID.randomUUID().toString();
    String location = mockMvc.perform(post("/v1/accounts").header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Freeze Replay\"}"))
        .andReturn().getResponse().getHeader("Location");
    var first = mockMvc.perform(post(location + "/freeze")
            .header("Authorization", "Bearer " + seedMerchantKey).header(KEY, key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"))
        .andReturn();
    // Re-freezing for real would be a 409 (account not ACTIVE) — the replay
    // must return the stored 200 instead.
    var retry = mockMvc.perform(post(location + "/freeze")
            .header("Authorization", "Bearer " + seedMerchantKey).header(KEY, key))
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

  @Test
  void amountsBeyondTheColumnBoundsAreRejectedAs400() throws Exception {
    mockMvc.perform(post("/v1/payment-intents").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":10000000000000000.0000}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("amount must fit numeric(19,4): at most 15 integer and 4 fraction digits"));
  }

  @Test
  void failedReexecutionAfterReclaimDoesNotPoisonTheKey() throws Exception {
    String accountId = mockMvc.perform(post("/v1/accounts").header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Poison Guard\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getHeader("Location");
    String publicId = accountId.substring(accountId.lastIndexOf('/') + 1);
    String key = UUID.randomUUID().toString();
    mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + publicId + "\",\"amount\":5.0000}"))
        .andExpect(status().isCreated());

    // Age the stored row past its expiry DB-side so the key is reclaimable.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE idempotency.idempotency_keys SET expires_at = now() - interval '1 minute'"
          + " WHERE key = '" + key + "'");
    }

    // Reclaim, then fail the re-execution inside the handler (unknown account
    // → 404 from the domain, thrown inside the aspect's transaction).
    mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":5.0000}"))
        .andExpect(status().isNotFound());

    // The failed re-execution must have rolled the reclaim back too: the slot
    // is still expired, so the next retry reclaims cleanly and runs as new —
    // not a 422 from a poisoned response-less row with a fresh expiry.
    mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + publicId + "\",\"amount\":5.0000}"))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotency-Replayed"));
  }

  @Test
  void encodedPathThatBypassesTheFilterStillFailsClosed() throws Exception {
    // "/v%31/" decodes to "/v1/" — an encoded take on a merchant route must
    // never slip past the gates unauthenticated. Auth precedes idempotency
    // (MerchantScopingTest pins the ordering), so the credential-less encoded
    // POST fails closed with 401 here, not the idempotency 400.
    mockMvc.perform(post(URI.create("/v%31/accounts"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Encoded Merchant\"}"))
        .andExpect(status().isUnauthorized());
  }
}
