package com.leandrossb.nummus.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class PaymentsRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  private String createAccount() {
    return accountsService.open(new OpenAccountCommand("Rest Merchant"))
        .publicId().toString();
  }

  private String createIntent(String accountId, String amountJson) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/payment-intents")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":" + amountJson + "}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReturns201WithIntentBody() throws Exception {
    String accountId = createAccount();
    mockMvc.perform(post("/v1/payment-intents")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":10.0000}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.publicId").exists())
        .andExpect(jsonPath("$.accountId").value(accountId))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.chargeId").exists())
        .andExpect(jsonPath("$.settledAt").doesNotExist());
  }

  @Test
  void payerPaymentThenGetSettlesAndCreditsMerchantBalance() throws Exception {
    String accountId = createAccount();
    String location = createIntent(accountId, "10.0000");

    String chargeId = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString(), "$.chargeId");
    simulator.pay(UUID.fromString(chargeId));

    mockMvc.perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.settledAt").exists());

    mockMvc.perform(get("/v1/accounts/{id}/balance", UUID.fromString(accountId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(10.0000))
        .andExpect(jsonPath("$.currency").value("BRL"));
  }

  @Test
  void expiredIntentRefusesSettlement() throws Exception {
    String accountId = createAccount();
    String location = createIntent(accountId, "5.0000");
    String chargeId = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString(), "$.chargeId");

    // age the intent past its expiry through the database (60s minimum ttl)
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.payment_intent SET expires_at = now() - interval '1 second'");
    }
    simulator.pay(UUID.fromString(chargeId));

    mockMvc.perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
  }

  @Test
  void failedChargeFailsTheIntentOverRest() throws Exception {
    String accountId = createAccount();
    String location = createIntent(accountId, "5.0000");
    String chargeId = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString(), "$.chargeId");
    simulator.fail(UUID.fromString(chargeId));

    mockMvc.perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
  }

  @Test
  void frozenAccountSettleConflictThenUnfreezeSettles() throws Exception {
    String accountId = createAccount();
    String location = createIntent(accountId, "5.0000");
    String chargeId = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString(), "$.chargeId");
    simulator.pay(UUID.fromString(chargeId));
    accountsService.freeze(UUID.fromString(accountId));

    mockMvc.perform(get(location))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());

    mockMvc.perform(get(location))
        .andExpect(status().isConflict());

    accountsService.unfreeze(UUID.fromString(accountId));
    mockMvc.perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    mockMvc.perform(get("/v1/accounts/{id}/balance", UUID.fromString(accountId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(5.0000));
  }

  @Test
  void validationFailuresReturn400() throws Exception {
    String accountId = createAccount();
    mockMvc.perform(post("/v1/payment-intents")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":0.0000}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/payment-intents")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":1.12345}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/payment-intents")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":5.0000}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownAccountAndIntentReturn404() throws Exception {
    mockMvc.perform(post("/v1/payment-intents")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":5.0000}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/v1/payment-intents/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }
}
