package com.leandrossb.nummus.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class IdempotencyMerchantNamespaceTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth;

  /** One operator key per test — merchant creation and conciliation ingest are operator-gated. */
  private String operatorAuth() {
    if (operatorAuth == null) {
      operatorAuth = ApiDrivers.operatorAuth(operatorKeys);
    }
    return operatorAuth;
  }

  private String openAccount(String bearer, String holder) throws Exception {
    return mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"" + holder + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
  }

  @Test
  void theSameKeyExecutesIndependentlyPerMerchant() throws Exception {
    String a = ApiDrivers.createMerchantAndGetKey(mockMvc, operatorAuth(), "Idem A");
    String b = ApiDrivers.createMerchantAndGetKey(mockMvc, operatorAuth(), "Idem B");
    openAccount(a, "Holder A");
    openAccount(b, "Holder B");
    String sharedKey = UUID.randomUUID().toString();
    String body = "{\"holderName\":\"Shared Key Holder\"}";

    var first = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + a).header(KEY, sharedKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();
    var second = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + b).header(KEY, sharedKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotency-Replayed"))
        .andReturn();
    // Different accounts (independent executions), NOT a replay of A's response.
    org.junit.jupiter.api.Assertions.assertNotEquals(
        first.getResponse().getHeader("Location"), second.getResponse().getHeader("Location"));
    // Within one merchant the key replays.
    mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + b).header(KEY, sharedKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
  }

  @Test
  void operatorNamespaceStaysUniqueWithoutAMerchant() throws Exception {
    // Operator POSTs (conciliation ingest) reserve with NULL merchant; the same
    // key still replays within the operator namespace.
    String shared = UUID.randomUUID().toString();
    String body = "{\"from\":\"2026-09-19T10:00:00Z\",\"to\":\"2026-09-19T11:00:00Z\"}";
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, shared).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, shared).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
  }
}
