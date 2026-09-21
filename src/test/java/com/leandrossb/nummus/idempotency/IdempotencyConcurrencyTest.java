package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class IdempotencyConcurrencyTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ApiKeysService apiKeys;

  private String seedMerchantKey;

  /** Accounts and payment-intent routes are merchant routes; this class
   *  keeps its fixtures under the seed merchant and authenticates as a
   *  minted seed key. */
  @BeforeEach
  void mintSeedMerchantKey() {
    seedMerchantKey = apiKeys.create(SeedMerchant.PUBLIC_ID, null).secret();
  }

  @Test
  @Timeout(120)
  void racingIdenticalPostsExecuteOnceAndReplayTheSameResponse() throws Exception {
    String accountId = JsonPath.read(mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Race Key Merchant\"}"))
        .andReturn().getResponse().getContentAsString(), "$.publicId");
    String key = UUID.randomUUID().toString();
    String body = "{\"accountId\":\"" + accountId + "\",\"amount\":3.0000}";

    int workers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MvcResult>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < workers; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return mockMvc.perform(post("/v1/payment-intents")
                  .header("Authorization", "Bearer " + seedMerchantKey)
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON).content(body))
              .andReturn();
        }));
      }
      start.countDown();
      for (Future<MvcResult> future : futures) {
        future.get(90, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    int created = 0;
    int replayed = 0;
    String referenceBody = null;
    for (Future<MvcResult> future : futures) {
      MvcResult result = future.get(1, TimeUnit.SECONDS);
      assertEquals(201, result.getResponse().getStatus());
      if (Boolean.parseBoolean(result.getResponse().getHeader("Idempotency-Replayed"))) {
        replayed++;
      } else {
        created++;
      }
      if (referenceBody == null) {
        referenceBody = result.getResponse().getContentAsString();
      } else {
        assertEquals(referenceBody, result.getResponse().getContentAsString());
      }
    }
    assertEquals(1, created);
    assertEquals(workers - 1, replayed);

    // Ground truth: one intent row for the account.
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM payments.payment_intent WHERE account_public_id = '"
            + accountId + "'")) {
      rs.next();
      assertEquals(1, rs.getInt(1));
    }
  }
}
