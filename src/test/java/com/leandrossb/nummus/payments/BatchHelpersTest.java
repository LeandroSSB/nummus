package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.FeeQuotes;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The page-composition helpers the listings build on: per-merchant account
 *  ids, one-schedule fee quotes for a batch, and grouped refunded totals. */
@AutoConfigureMockMvc
class BatchHelpersTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private FeeQuotes quotes;

  private UUID merchantId;
  private UUID otherMerchantId;
  private PaymentIntent settled;

  @BeforeEach
  void createFixtures() throws Exception {
    merchantId = createMerchant("Batch Helpers Merchant");
    otherMerchantId = createMerchant("Batch Helpers Other Merchant");
    var account = accountsService.open(merchantId, new OpenAccountCommand("Batch Helpers Account"));
    settled = createAndSettleIntent(account.publicId(), "100.0000");
    refunds.create(merchantId, settled.publicId(),
        new CreateRefundCommand(Money.ofBrl("25.0000"), Duration.ofMinutes(10)));
    refunds.create(merchantId, settled.publicId(),
        new CreateRefundCommand(Money.ofBrl("5.0000"), Duration.ofMinutes(10)));
    payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("40.0000"), Duration.ofMinutes(10)));
  }

  @Test
  void listPublicIdsReturnsOnlyThatMerchantsAccounts() {
    var ids = accountsService.listPublicIds(merchantId);
    assertEquals(1, ids.size());
    assertEquals(settled.accountPublicId(), ids.get(0));
    assertEquals(List.of(), accountsService.listPublicIds(otherMerchantId));
  }

  @Test
  void quotesForUsesSettledFactAndQuotesOpenIntentsFromOneSweep() {
    var open = payments.create(merchantId,
        new CreateIntentCommand(settled.accountPublicId(), Money.ofBrl("40.0000"), Duration.ofMinutes(10)));
    var map = quotes.quotesFor(merchantId, List.of(settled, open));
    assertEquals(2, map.size());
    // Settled intent: the charged fact (zero schedule → fee 0, net = gross).
    assertEquals(0, map.get(settled.publicId()).fee().compareTo(Money.ofBrl("0.0000")));
    assertEquals(0, map.get(settled.publicId()).netAmount().compareTo(Money.ofBrl("100.0000")));
    // Open intent: the current schedule's quote (zero schedule here).
    assertEquals(0, map.get(open.publicId()).netAmount().compareTo(Money.ofBrl("40.0000")));
    assertEquals(Map.of(), quotes.quotesFor(merchantId, List.of()));
  }

  @Test
  void refundedTotalsGroupHeldAndSettledRefundsPerIntent() {
    var unknown = UUID.randomUUID();
    var map = refunds.refundedTotals(List.of(settled.publicId(), unknown));
    assertEquals(2, map.size());
    assertEquals(0, map.get(settled.publicId()).compareTo(Money.ofBrl("30.0000")));
    assertEquals(0, map.get(unknown).compareTo(Money.ofBrl("0.0000")));
  }

  private PaymentIntent createAndSettleIntent(UUID accountPublicId, String amount) {
    var intent = payments.create(merchantId,
        new CreateIntentCommand(accountPublicId, Money.ofBrl(amount), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    return payments.get(merchantId, intent.publicId());
  }

  private UUID createMerchant(String name) throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return UUID.fromString(com.jayway.jsonpath.JsonPath
        .read(created.getResponse().getContentAsString(), "$.merchantId"));
  }
}
