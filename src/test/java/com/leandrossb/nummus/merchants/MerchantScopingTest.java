package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class MerchantScopingTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth;

  /** One operator key per test — merchant creation is operator-gated. */
  private String operatorAuth() {
    if (operatorAuth == null) {
      operatorAuth = "Bearer " + operatorKeys.create().secret();
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

  private String openAccount(String bearer) throws Exception {
    MvcResult opened = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Scoped Holder\"}"))
        .andExpect(status().isCreated()).andReturn();
    return opened.getResponse().getHeader("Location");
  }

  @Test
  void merchantARoutesCannotSeeMerchantBResources() throws Exception {
    String a = createMerchantAndGetKey("Merchant A");
    String b = createMerchantAndGetKey("Merchant B");
    String aLocation = openAccount(a);

    // The owner sees it; the other merchant gets 404 on every surface.
    mockMvc.perform(get(aLocation).header("Authorization", "Bearer " + a))
        .andExpect(status().isOk());
    mockMvc.perform(get(aLocation).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(aLocation + "/balance").header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(aLocation + "/statement").header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(post(aLocation + "/freeze")
            .header("Authorization", "Bearer " + b).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
    // Unauthenticated access is 401, and authentication precedes idempotency.
    mockMvc.perform(get(aLocation)).andExpect(status().isUnauthorized());
    mockMvc.perform(post(aLocation + "/freeze")
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void intentsAreInvisibleAcrossMerchants() throws Exception {
    String a = createMerchantAndGetKey("Intent A");
    String b = createMerchantAndGetKey("Intent B");
    String accountLocation = openAccount(a);
    String accountId = accountLocation.substring(accountLocation.lastIndexOf('/') + 1);
    MvcResult intentCreated = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + a)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":5.0000}"))
        .andExpect(status().isCreated()).andReturn();
    String intentLocation = intentCreated.getResponse().getHeader("Location");

    mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + a))
        .andExpect(status().isOk());
    mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    // B cannot create an intent against A's account.
    mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + b)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":5.0000}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void webhookEndpointsAreIsolatedPerMerchant() throws Exception {
    String a = createMerchantAndGetKey("Hook A");
    String b = createMerchantAndGetKey("Hook B");
    MvcResult endpoint = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + a)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://a.example/hook\"}"))
        .andExpect(status().isCreated()).andReturn();
    String location = endpoint.getResponse().getHeader("Location");

    mockMvc.perform(get(location).header("Authorization", "Bearer " + a))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/webhook-endpoints").header("Authorization", "Bearer " + b))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
    mockMvc.perform(get(location).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(location + "/deliveries").header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(location)
            .header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
  }
}
