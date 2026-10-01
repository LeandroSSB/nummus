package com.leandrossb.nummus.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Caps bound single money-moving operations: over-cap writes reject 422 and
 *  leave no row anywhere; at-cap passes; unset is unlimited. */
@AutoConfigureMockMvc
class PaymentLimitsEnforcementTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  private String operatorAuth;
  private String merchantKey;
  private UUID merchantId;
  private UUID accountId;

  @BeforeEach
  void createFixtures() throws Exception {
    operatorAuth = "Bearer " + operatorKeys.create("enforce-probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Limits Enforcement Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
    accountId = accountsService.open(merchantId, new OpenAccountCommand("Enforcement Account"))
        .publicId();
  }

  private void setLimits(String intentCap, String payoutCap) throws Exception {
    String content = "{";
    if (intentCap != null) {
      content += "\"maxIntentAmount\":" + intentCap;
    }
    if (payoutCap != null) {
      content += (content.length() > 1 ? "," : "") + "\"maxPayoutAmount\":" + payoutCap;
    }
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content + "}"))
        .andExpect(status().isOk());
  }

  @Test
  void overCapIntentRejects422AndLeavesNoRow() throws Exception {
    setLimits("100.0000", null);
    mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":100.0001}"))
        .andExpect(status().isUnprocessableEntity());
    // The M22 listing proves no intent row exists for the rejection.
    mockMvc.perform(get("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void atCapIntentPassesInclusively() throws Exception {
    setLimits("100.0000", null);
    mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":100.0000}"))
        .andExpect(status().isCreated());
  }

  @Test
  void overCapPayoutRejects422BeforeAnyReservationOrTransfer() throws Exception {
    setLimits(null, "50.0000");
    var funding = payments.create(merchantId,
        new CreateIntentCommand(accountId, Money.ofBrl("500.0000"), Duration.ofMinutes(10)));
    simulator.pay(funding.chargePublicId());
    payments.get(merchantId, funding.publicId());
    var destination = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);

    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":50.0001,"
                + "\"bankAccountId\":\"" + destination.publicId() + "\"}"))
        .andExpect(status().isUnprocessableEntity());

    // No payout row, and the balance is untouched (no reservation taken).
    mockMvc.perform(get("/v1/payouts")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc.perform(get("/v1/accounts/{id}/balance", accountId)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(500.0000));
  }

  @Test
  void unsetCapsStayUnlimited() throws Exception {
    mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":1000000.0000}"))
        .andExpect(status().isCreated());
  }
}
