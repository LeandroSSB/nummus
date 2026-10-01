package com.leandrossb.nummus.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.domain.Payout;
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

/** One account walked through its money movements, asserting the three
 *  balance figures at every step: booked amount, pendingIncoming (not yet in
 *  balance), reservedOutgoing (already out of balance, not yet executed).
 *  The merchant carries a payoutFixedAmount fee of 1.5000 so the payout leg
 *  pins the rule the spec states: the fee debits at execution, never at
 *  reservation. */
@AutoConfigureMockMvc
class BalanceCompositionRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  private UUID merchantId;
  private String merchantKey;
  private PaymentAccount account;

  @BeforeEach
  void createMerchantWithPayoutFeeFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Composition Lifecycle Merchant\","
                + "\"fee\":{\"rate\":0.0,\"fixedAmount\":0.0,"
                + "\"payoutFixedAmount\":1.5}}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
    account = accountsService.open(merchantId, new OpenAccountCommand("Lifecycle Merchant"));
  }

  private void assertFigures(String amount, String pendingIncoming, String reservedOutgoing)
      throws Exception {
    mockMvc.perform(get("/v1/accounts/{id}/balance", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(Double.parseDouble(amount)))
        .andExpect(jsonPath("$.pendingIncoming").value(Double.parseDouble(pendingIncoming)))
        .andExpect(jsonPath("$.reservedOutgoing").value(Double.parseDouble(reservedOutgoing)));
    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(Double.parseDouble(amount)))
        .andExpect(jsonPath("$.pendingIncoming").value(Double.parseDouble(pendingIncoming)))
        .andExpect(jsonPath("$.reservedOutgoing").value(Double.parseDouble(reservedOutgoing)));
  }

  @Test
  void compositionTracksTheFullMoneyLifecycle() throws Exception {
    assertFigures("0.0000", "0.0000", "0.0000");

    // Inbound: created → pendingIncoming; paid → still pendingIncoming.
    var intent = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), javaMoney("100.0000"), Duration.ofMinutes(10)));
    assertFigures("0.0000", "100.0000", "0.0000");
    simulator.pay(intent.chargePublicId());
    payments.get(merchantId, intent.publicId());
    // Settled: balance +net (rate 0, fixed 0 → net = gross), pendingIncoming −gross.
    assertFigures("100.0000", "0.0000", "0.0000");

    // Outbound: payout request reserves amount (fee NOT yet debited)…
    var destination = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    Payout payout = payouts.create(merchantId, new CreatePayoutCommand(account.publicId(),
        javaMoney("40.0000"), destination.publicId(), Duration.ofMinutes(10)));
    assertFigures("60.0000", "0.0000", "40.0000");

    // …execution releases the reservation and debits the fee leg.
    simulator.payTransfer(payout.transferPublicId());
    payouts.get(merchantId, payout.publicId());
    assertFigures("58.5000", "0.0000", "0.0000");

    // Refund: request reserves the amount; execution moves no further balance
    // (fees are retained, never reversed).
    var refund = refunds.create(merchantId, intent.publicId(),
        new CreateRefundCommand(javaMoney("10.0000"), Duration.ofMinutes(10)));
    assertFigures("48.5000", "0.0000", "10.0000");
    simulator.payRefund(refund.networkRefundPublicId());
    refunds.get(merchantId, refund.publicId());
    assertFigures("48.5000", "0.0000", "0.0000");
  }

  private static com.leandrossb.nummus.ledger.domain.Money javaMoney(String amount) {
    return com.leandrossb.nummus.ledger.domain.Money.ofBrl(amount);
  }
}
