package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Fan-out is audience-scoped: merchant B never receives merchant A's
 *  payment events. This pins the tenancy property. */
@AutoConfigureMockMvc
class WebhookAudienceTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private String createMerchantAndGetKey(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  private String registerEndpointAndGetId(String bearer) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/audience-" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.publicId");
  }

  private void settleSomething(String bearer) throws Exception {
    MvcResult opened = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Audience Holder\"}"))
        .andExpect(status().isCreated()).andReturn();
    String accountLocation = opened.getResponse().getHeader("Location");
    String accountId = accountLocation.substring(accountLocation.lastIndexOf('/') + 1);
    MvcResult intent = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":5.0000}"))
        .andExpect(status().isCreated()).andReturn();
    String intentLocation = intent.getResponse().getHeader("Location");
    // The charge id travels on the intent; pay it in the simulator, then GET settles.
    MvcResult fetched = mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk()).andReturn();
    String chargeId = com.jayway.jsonpath.JsonPath.read(fetched.getResponse().getContentAsString(), "$.chargeId");
    mockMvc.perform(post("/simulator/charges/" + chargeId + "/pay")).andExpect(status().isOk());
    mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk());
  }

  @Test
  void settledEventDeliversOnlyToTheOwningMerchantsEndpoint() throws Exception {
    String a = createMerchantAndGetKey("Audience A");
    String b = createMerchantAndGetKey("Audience B");
    String aEndpoint = registerEndpointAndGetId(a);
    String bEndpoint = registerEndpointAndGetId(b);

    settleSomething(a);

    MvcResult aDeliveries = mockMvc.perform(get("/v1/webhook-endpoints/" + aEndpoint + "/deliveries")
            .header("Authorization", "Bearer " + a))
        .andExpect(status().isOk()).andReturn();
    MvcResult bDeliveries = mockMvc.perform(get("/v1/webhook-endpoints/" + bEndpoint + "/deliveries")
            .header("Authorization", "Bearer " + b))
        .andExpect(status().isOk()).andReturn();
    int aCount = com.jayway.jsonpath.JsonPath.read(aDeliveries.getResponse().getContentAsString(), "$.length()");
    int bCount = com.jayway.jsonpath.JsonPath.read(bDeliveries.getResponse().getContentAsString(), "$.length()");
    Assertions.assertTrue(aCount >= 1, "owner must receive the settled event");
    Assertions.assertEquals(0, bCount, "another merchant's endpoint must receive nothing");
  }
}
