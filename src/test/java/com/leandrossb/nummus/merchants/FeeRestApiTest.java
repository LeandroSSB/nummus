package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class FeeRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private OperatorKeysService operatorKeys;
  @Autowired
  private SimulatorService simulator;

  private record Merchant(String key, String id) {}

  /** Creates a merchant over HTTP with the given fee body (null = none) and returns its key + id. */
  private Merchant newMerchant(String feeBody) throws Exception {
    var created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(feeBody == null ? "{\"name\":\"fee api merchant\"}"
                : "{\"name\":\"fee api merchant\",\"fee\":" + feeBody + "}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    return new Merchant(JsonPath.read(body, "$.apiKey.secret"), JsonPath.read(body, "$.merchantId"));
  }

  /** Opens a payment account as the merchant; returns its public id. */
  private String openAccount(String merchantKey) throws Exception {
    var account = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + merchantKey).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"fee api account\"}"))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(account.getResponse().getContentAsString(), "$.publicId");
  }

  private String createIntent(String merchantKey, String accountId, String amount) throws Exception {
    var intent = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":" + amount + "}"))
        .andExpect(status().isCreated()).andReturn();
    return intent.getResponse().getContentAsString();
  }

  /** A merchant id that exists — for 400-path assertions that must not 404 first. */
  private String anyMerchantId() throws Exception {
    return newMerchant(null).id();
  }

  @Test
  void operatorUpdatesFeeAndReplayIsIdempotent() throws Exception {
    var merchant = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"fee target\"}"))
        .andExpect(status().isCreated()).andReturn();
    String id = com.jayway.jsonpath.JsonPath.read(merchant.getResponse().getContentAsString(), "$.merchantId");
    String idem = UUID.randomUUID().toString();

    var first = mockMvc.perform(put("/v1/merchants/" + id + "/fee")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, idem)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rate\":0.0099,\"fixedAmount\":0.39}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee.rate").value(0.0099))
        .andExpect(jsonPath("$.fee.fixedAmount").value(0.39)).andReturn();
    var replay = mockMvc.perform(put("/v1/merchants/" + id + "/fee")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, idem)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rate\":0.0099,\"fixedAmount\":0.39}"))
        .andExpect(status().isOk()).andReturn();
    org.junit.jupiter.api.Assertions.assertEquals(first.getResponse().getContentAsString(),
        replay.getResponse().getContentAsString());

    mockMvc.perform(get("/v1/merchants/" + id).header("Authorization", ApiDrivers.operatorAuth(operatorKeys)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee.rate").value(0.0099));
  }

  @Test
  void invalidFeeIsBadRequestAndUnknownMerchantIsNotFound() throws Exception {
    mockMvc.perform(put("/v1/merchants/" + UUID.randomUUID() + "/fee")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"rate\":0.0099,\"fixedAmount\":0.39}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(put("/v1/merchants/" + anyMerchantId() + "/fee")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"rate\":1.0,\"fixedAmount\":0}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put("/v1/merchants/" + anyMerchantId() + "/fee")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"rate\":-0.1,\"fixedAmount\":0}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void merchantKeyCannotUpdateFees() throws Exception {
    String key = newMerchant(null).key();
    mockMvc.perform(put("/v1/merchants/" + UUID.randomUUID() + "/fee")
            .header("Authorization", "Bearer " + key).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"rate\":0.01,\"fixedAmount\":0}"))
        .andExpect(status().isForbidden()); // M8 role mismatch
  }

  @Test
  void createMerchantWithFeeCarriesItInTheResponse() throws Exception {
    mockMvc.perform(post("/v1/merchants")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"fee born\",\"fee\":{\"rate\":0.015,\"fixedAmount\":0}}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.fee.rate").value(0.015));
  }

  @Test
  void intentResponsesQuoteTheCurrentRate() throws Exception {
    var merchant = newMerchant("{\"rate\":0.01,\"fixedAmount\":0.5}");
    String accountId = openAccount(merchant.key());
    String intent = createIntent(merchant.key(), accountId, "100.00");
    Assertions.assertEquals(1.5,
        ((Number) JsonPath.read(intent, "$.fee")).doubleValue());
    Assertions.assertEquals(98.5,
        ((Number) JsonPath.read(intent, "$.netAmount")).doubleValue());

    // Rate change moves the estimate on read; nothing is frozen per intent.
    mockMvc.perform(put("/v1/merchants/" + merchant.id() + "/fee")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"rate\":0.02,\"fixedAmount\":0}"))
        .andExpect(status().isOk());
    String intentId = JsonPath.read(intent, "$.publicId");
    mockMvc.perform(get("/v1/payment-intents/" + intentId)
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee").value(2.0))
        .andExpect(jsonPath("$.netAmount").value(98.0));
  }

  @Test
  void settledIntentShowsTheChargedFactNotTheCurrentEstimate() throws Exception {
    var merchant = newMerchant("{\"rate\":0.01,\"fixedAmount\":0}");
    String accountId = openAccount(merchant.key());
    String intent = createIntent(merchant.key(), accountId, "100.00");
    String intentId = JsonPath.read(intent, "$.publicId");
    String chargeId = JsonPath.read(intent, "$.chargeId");
    simulator.pay(UUID.fromString(chargeId));
    // Settlement is lazy on read: settle now, while the 0.01 rate is in force —
    // the fee fact freezes with the money movement.
    mockMvc.perform(get("/v1/payment-intents/" + intentId)
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    mockMvc.perform(put("/v1/merchants/" + merchant.id() + "/fee")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"rate\":0.05,\"fixedAmount\":0}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/payment-intents/" + intentId)
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.fee").value(1.0))       // settle-time rate, not 5.0
        .andExpect(jsonPath("$.netAmount").value(99.0));
  }
}
