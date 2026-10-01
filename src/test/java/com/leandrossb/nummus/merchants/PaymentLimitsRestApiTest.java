package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;

/** Operator-governed limits over REST: set, read, attributed history —
 *  and nothing a merchant key can reach. */
@AutoConfigureMockMvc
class PaymentLimitsRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth;
  private UUID merchantId;
  private String merchantKey;

  @BeforeEach
  void createFixtures() throws Exception {
    operatorAuth = "Bearer " + operatorKeys.create("limits-rest-probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Payment Limits Rest Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
  }

  @Test
  void putStoresLimitsAndGetReturnsThem() throws Exception {
    mockMvc.perform(get("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxIntentAmount").doesNotExist())
        .andExpect(jsonPath("$.maxPayoutAmount").doesNotExist());

    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":5000.0000,\"maxPayoutAmount\":2000.0000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxIntentAmount").value(5000.0000))
        .andExpect(jsonPath("$.maxPayoutAmount").value(2000.0000));

    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxPayoutAmount\":1500.0000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxIntentAmount").doesNotExist())
        .andExpect(jsonPath("$.maxPayoutAmount").value(1500.0000));
  }

  @Test
  void historyListsAttributedChangesNewestFirst() throws Exception {
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":100.0000}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/merchants/{id}/limits-history", merchantId)
            .header("Authorization", operatorAuth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].maxIntentAmount").value(100.0000))
        .andExpect(jsonPath("$[0].createdByLabel").value("limits-rest-probe"));
  }

  @Test
  void invalidBodiesAndUnknownMerchantsAreRejected() throws Exception {
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":-5}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put("/v1/merchants/{id}/limits", UUID.randomUUID())
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":5}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void merchantKeysCannotReachTheOperatorSurface() throws Exception {
    mockMvc.perform(get("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isForbidden());
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());
  }
}
