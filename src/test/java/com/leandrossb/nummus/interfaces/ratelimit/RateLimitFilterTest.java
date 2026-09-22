package com.leandrossb.nummus.interfaces.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Tight buckets per test class; every test mints fresh merchants/keys, so
 *  each gets a fresh bucket and tests cannot interfere. The operator bucket
 *  never refills — the burst assertion is timing-independent; {@code Retry-After}
 *  may be large, which the test only checks for existence. */
@AutoConfigureMockMvc
class RateLimitFilterTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @DynamicPropertySource
  static void tightBuckets(DynamicPropertyRegistry registry) {
    registry.add("nummus.ratelimit.merchant-capacity", () -> "2");
    registry.add("nummus.ratelimit.merchant-refill-per-second", () -> "1");
    registry.add("nummus.ratelimit.operator-capacity", () -> "3");
    registry.add("nummus.ratelimit.operator-refill-per-second", () -> "0");
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void merchantBucketRejectsWith429AndRetryAfter() throws Exception {
    String bearer = "Bearer " + ApiDrivers.createMerchantAndGetKey(
            mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Limited Merchant");
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.status").value(429));
  }

  @Test
  void bucketRefillsOverTime() throws Exception {
    String bearer = "Bearer " + ApiDrivers.createMerchantAndGetKey(
            mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Refill Merchant");
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isTooManyRequests());
    // At 1 token/s, 2.5s restores the drained bucket (capped at capacity).
    Thread.sleep(2500);
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
  }

  @Test
  void merchantsHaveIsolatedBuckets() throws Exception {
    String a = "Bearer " + ApiDrivers.createMerchantAndGetKey(
            mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Isolated A");
    String b = "Bearer " + ApiDrivers.createMerchantAndGetKey(
            mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Isolated B");
    mockMvc.perform(get("/v1/me").header("Authorization", a)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", a)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", a))
        .andExpect(status().isTooManyRequests());
    // B's bucket is untouched.
    mockMvc.perform(get("/v1/me").header("Authorization", b)).andExpect(status().isOk());
  }

  @Test
  void operatorBucketRejectsIndependently() throws Exception {
    String operator = ApiDrivers.operatorAuth(operatorKeys);
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/v1/merchants")
              .header("Authorization", operator)
              .header(KEY, UUID.randomUUID().toString())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"Operator Burst " + i + "\"}"))
          .andExpect(status().isCreated());
    }
    mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operator)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Operator Over\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }

  @Test
  void unauthenticatedPassesThroughAndA429StoresNoIdempotencyRow() throws Exception {
    // No bearer: repeated 401s — the 401 path is never throttled.
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(get("/v1/me")).andExpect(status().isUnauthorized());
    }
    String idemKey = UUID.randomUUID().toString();
    String bearer = "Bearer " + ApiDrivers.createMerchantAndGetKey(
            mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Rowless Merchant");
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    // The rate-limited POST is refused pre-controller: no idempotency row.
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, idemKey)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isTooManyRequests());
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(
            "select count(*) from idempotency.idempotency_keys where key = '" + idemKey + "'")) {
      org.junit.jupiter.api.Assertions.assertTrue(rs.next());
      org.junit.jupiter.api.Assertions.assertEquals(0, rs.getInt(1));
    }
  }
}
