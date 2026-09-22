package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/** Gating E2E over the operator surfaces: keyless is 401, merchant keys are
 *  403 in both directions, and the operator's own writes keep working —
 *  merchant creation and conciliation ingest, replaying in the NULL namespace.
 *  This context leaves the bootstrap token unset, so the unconfigured
 *  bootstrap's 404 is pinned here too. */
@AutoConfigureMockMvc
class OperatorGatingTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth;

  /** One operator key per test, minted via the service (no bootstrap dependency). */
  private String operatorAuth() {
    if (operatorAuth == null) {
      operatorAuth = ApiDrivers.operatorAuth(operatorKeys);
    }
    return operatorAuth;
  }

  @Test
  void operatorRoutesRejectKeylessAndMerchantKeys() throws Exception {
    String merchantKey = ApiDrivers.createMerchantAndGetKey(
            mockMvc, operatorAuth(), "Op Fixture Merchant");
    mockMvc.perform(get("/v1/merchants/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/merchants/" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isForbidden());
    // Conciliation is gated the same way, and authentication precedes
    // idempotency validation: 401, never the keyless-write 400.
    mockMvc.perform(post("/v1/conciliation/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"2026-09-19T10:00:00Z\",\"to\":\"2026-09-19T11:00:00Z\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/conciliation/reports")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isForbidden());
    // The mirror direction: a merchant key never reaches operator self-service.
    mockMvc.perform(get("/v1/operator/api-keys")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isForbidden());
  }

  @Test
  void operatorCreatesMerchantsAndIngestsConciliation() throws Exception {
    // Merchant creation works as operator — and only as operator.
    mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Gated Merchant\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.merchantId").exists())
        .andExpect(jsonPath("$.apiKey.secret").isNotEmpty());
    String merchantKey = ApiDrivers.createMerchantAndGetKey(
            mockMvc, operatorAuth(), "Op Fixture Merchant");
    mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"No Self-Serve\"}"))
        .andExpect(status().isForbidden());

    // Operator ingest reserves and replays in the NULL idempotency namespace.
    String replayKey = UUID.randomUUID().toString();
    String body = "{\"from\":\"2026-09-19T10:00:00Z\",\"to\":\"2026-09-19T11:00:00Z\"}";
    String first = mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth()).header(KEY, replayKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String replay = mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth()).header(KEY, replayKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andReturn().getResponse().getContentAsString();
    org.junit.jupiter.api.Assertions.assertEquals(first, replay);
  }

  @Test
  void bootstrapIsUnavailableWhenUnconfigured() throws Exception {
    // No nummus.operator.bootstrap-token in this context: the endpoint is not
    // locked — it does not exist.
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"anything\"}"))
        .andExpect(status().isNotFound());
  }
}
