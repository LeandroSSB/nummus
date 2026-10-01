package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.application.MoneyInFlight;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutReservedAccount;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundReservedAccount;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
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

/** Reservations are real postings: while money-out is REQUESTED, the payments
 *  domain sums the API reports must equal what the reserved ledger accounts
 *  actually hold. A future change that books legs inconsistently fails here
 *  instead of drifting silently. */
@AutoConfigureMockMvc
class BalanceCompositionConsistencyTest extends IntegrationTestBase {

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
  void createFundedFixtureWithReservations() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Consistency Guard Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));

    account = accountsService.open(merchantId, new OpenAccountCommand("Consistency Merchant"));
    var funded = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("200.0000"), Duration.ofMinutes(10)));
    simulator.pay(funded.chargePublicId());
    payments.get(merchantId, funded.publicId());

    var destination = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    payouts.create(merchantId, new CreatePayoutCommand(account.publicId(),
        Money.ofBrl("70.0000"), destination.publicId(), Duration.ofMinutes(10)));
    refunds.create(merchantId, funded.publicId(),
        new CreateRefundCommand(Money.ofBrl("30.0000"), Duration.ofMinutes(10)));
  }

  @Test
  void requestedReservationsMatchTheReservedLedgerAccounts() {
    var reservedPostings = ledger.balance(PayoutReservedAccount.PUBLIC_ID).amount()
        .add(ledger.balance(RefundReservedAccount.PUBLIC_ID).amount())
        .negate();
    var domainSums = moneyInFlight.sums(account.publicId()).reservedOutgoing().amount();
    assertEquals(0, reservedPostings.compareTo(domainSums),
        () -> "reserved ledger accounts hold " + reservedPostings
            + " but the payments domain reports " + domainSums);
  }
}
