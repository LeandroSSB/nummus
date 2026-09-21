package com.leandrossb.nummus.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
      operatorAuth = "Bearer " + operatorKeys.create(null).secret();
    }
    return operatorAuth;
  }

  private String createMerchantAndGetKey(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
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
    String a = createMerchantAndGetKey("Idem A");
    String b = createMerchantAndGetKey("Idem B");
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
