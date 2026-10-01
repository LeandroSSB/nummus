package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.application.MoneyInFlight;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The in-flight sums the accounts module reads: pending settlement inbound
 *  and requested-but-unexecuted outbound, per payment account. */
@AutoConfigureMockMvc
class PaymentsMoneyInFlightTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private Ledger ledger;

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

  @Autowired
  private MoneyInFlight moneyInFlight;

  private UUID merchantId;
  private PaymentAccount account;

  @BeforeEach
  void createFundedFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Money In Flight Fixture Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    String merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));

    account = accountsService.open(merchantId, new OpenAccountCommand("In Flight Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "in flight house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new PostTransactionCommand("in flight funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("200.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("200.0000")))));
  }

  @Test
  void sumsAreZeroOnAFreshAccount() {
    var sums = moneyInFlight.sums(account.publicId());
    assertEquals(Money.ofBrl("0.0000"), sums.pendingIncoming());
    assertEquals(Money.ofBrl("0.0000"), sums.reservedOutgoing());
  }

  @Test
  void sumsSplitPendingSettlementFromRequestedMoneyOut() {
    // Inbound still pending settlement: created, never paid.
    payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("40.0000"), Duration.ofMinutes(10)));

    // A settled intent funds a refund and a payout; a second created intent
    // keeps pendingIncoming honest (settled money must not count as pending).
    var settled = createAndSettleIntent("60.0000");
    var destination = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    payouts.create(merchantId, new CreatePayoutCommand(account.publicId(),
        Money.ofBrl("25.0000"), destination.publicId(), Duration.ofMinutes(10)));
    refunds.create(merchantId, settled.publicId(),
        new CreateRefundCommand(Money.ofBrl("5.0000"), Duration.ofMinutes(10)));

    var sums = moneyInFlight.sums(account.publicId());
    assertEquals(Money.ofBrl("40.0000"), sums.pendingIncoming());
    assertEquals(Money.ofBrl("30.0000"), sums.reservedOutgoing());
  }

  private PaymentIntent createAndSettleIntent(String amount) {
    var intent = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    // Lazy terminal transition: a read resolves the paid charge into settlement.
    return payments.get(merchantId, intent.publicId());
  }
}
