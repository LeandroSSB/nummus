package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.application.TransfersService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.CreateTransferCommand;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.payments.domain.Transfer;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The remaining merchant listings as CSV — payouts, refunds, transfers:
 *  each header row is its response record's component names in declaration
 *  order, rows run newest first, the status filter narrows to the same zero
 *  settled rows the JSON listing returns, and the export cap truncates with a
 *  marker line instead of paging. No field here naturally carries the
 *  separator, so quoting itself is {@code CsvTest}'s to prove — these rows
 *  only pin that plain values pass through unwrapped. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "nummus.export.max-rows=3")
class RemainingCsvExportTest extends IntegrationTestBase {

  /** Fixtures this class settles and pays — backdated in
   *  {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

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
  private TransfersService transfers;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  private UUID merchantId;
  private String merchantKey;
  private UUID accountA;
  private UUID accountB;
  private UUID destinationId;
  private Payout firstPayout;
  private Payout newestPayout;
  private PaymentIntent refundableIntent;
  private Refund firstRefund;
  private Refund newestRefund;
  private Transfer firstTransfer;
  private Transfer newestTransfer;

  /** A fresh merchant per test over HTTP with two accounts: account A funded
   *  by one settled intent (payouts reserve against it, transfers move out of
   *  it), account B carrying the settled intent the refunds hold against.
   *  Every listing below is seeded with exactly two rows — under the class's
   *  export cap, so only the dedicated truncation test ever sees it bite. */
  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Remaining Csv Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    accountA = accountsService.open(merchantId, new OpenAccountCommand("Remaining Csv A")).publicId();
    accountB = accountsService.open(merchantId, new OpenAccountCommand("Remaining Csv B")).publicId();
    var funding = payments.create(merchantId,
        new CreateIntentCommand(accountA, Money.ofBrl("500.0000"), Duration.ofMinutes(10)));
    simulator.pay(funding.chargePublicId());
    payments.get(merchantId, funding.publicId());
    settledIntents.add(funding.publicId());
    networkCharges.add(funding.chargePublicId());
    destinationId = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId).publicId();
    firstPayout = payouts.create(merchantId, new CreatePayoutCommand(accountA, Money.ofBrl("50.0000"),
        destinationId, Duration.ofMinutes(10)));
    newestPayout = payouts.create(merchantId, new CreatePayoutCommand(accountA, Money.ofBrl("20.0000"),
        destinationId, Duration.ofMinutes(10)));
    refundableIntent = createAndSettleIntent(accountB, "200.0000");
    firstRefund = refunds.create(merchantId, refundableIntent.publicId(),
        new CreateRefundCommand(Money.ofBrl("25.0000"), Duration.ofMinutes(10)));
    newestRefund = refunds.create(merchantId, refundableIntent.publicId(),
        new CreateRefundCommand(Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    firstTransfer = transfers.create(merchantId,
        new CreateTransferCommand(accountA, accountB, Money.ofBrl("5.0000")));
    newestTransfer = transfers.create(merchantId,
        new CreateTransferCommand(accountA, accountB, Money.ofBrl("2.0000")));
    // Re-read each row so the assertions compare the persisted instants —
    // the CSV renders what the store returns, not the pre-insert objects.
    firstPayout = payouts.get(merchantId, firstPayout.publicId());
    newestPayout = payouts.get(merchantId, newestPayout.publicId());
    firstRefund = refunds.get(merchantId, firstRefund.publicId());
    newestRefund = refunds.get(merchantId, newestRefund.publicId());
    firstTransfer = transfers.get(merchantId, firstTransfer.publicId());
    newestTransfer = transfers.get(merchantId, newestTransfer.publicId());
  }

  /** The settle recipe the refund suites use: create the intent, pay the
   *  charge, first poll settles — both rows tracked for the class sweep. */
  private PaymentIntent createAndSettleIntent(UUID accountPublicId, String amount) {
    var intent = payments.create(merchantId,
        new CreateIntentCommand(accountPublicId, Money.ofBrl(amount), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    return payments.get(merchantId, intent.publicId());
  }

  @Test
  void payoutsCsvCarriesEveryPayoutColumnNewestFirst() throws Exception {
    MvcResult result = mockMvc.perform(get("/v1/payouts")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.startsWith("attachment; filename=\"payouts-")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    assertEquals("publicId,accountId,amount,currency,destinationBankKey,bankAccountId,status,"
        + "transferId,expiresAt,createdAt,settledAt,fee,reservationTransactionId", lines[0]);
    assertEquals(3, lines.length); // header + the two fixture payouts

    // Newest first, every column in the declaration order above — the
    // settlement columns stay empty while the payout is still REQUESTED.
    String[] row = lines[1].split(",", -1);
    assertEquals(13, row.length);
    assertEquals(newestPayout.publicId().toString(), row[0]);
    assertEquals(accountA.toString(), row[1]);
    assertEquals("20.0000", row[2]);
    assertEquals("BRL", row[3]);
    assertEquals(newestPayout.destinationBankKey(), row[4]);
    assertEquals(destinationId.toString(), row[5]);
    assertEquals("REQUESTED", row[6]);
    assertEquals(newestPayout.transferPublicId().toString(), row[7]);
    assertEquals(newestPayout.expiresAt().toString(), row[8]);
    assertEquals(newestPayout.createdAt().toString(), row[9]);
    assertEquals("", row[10]);
    assertEquals("", row[11]);
    assertEquals(newestPayout.requestTransactionPublicId().toString(), row[12]);
    assertEquals(firstPayout.publicId().toString(), lines[2].split(",", -1)[0]);
  }

  @Test
  void settledPayoutFilterYieldsTheHeaderAlone() throws Exception {
    MvcResult result = mockMvc.perform(get("/v1/payouts")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv")
            .param("status", "SETTLED"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    assertEquals(1, lines.length); // nothing settled: the header row only
    assertEquals("publicId,accountId,amount,currency,destinationBankKey,bankAccountId,status,"
        + "transferId,expiresAt,createdAt,settledAt,fee,reservationTransactionId", lines[0]);
  }

  @Test
  void refundsCsvCarriesEveryRefundColumnNewestFirst() throws Exception {
    MvcResult result = mockMvc.perform(get("/v1/refunds")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.startsWith("attachment; filename=\"refunds-")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    assertEquals("publicId,intentId,amount,currency,status,networkRefundId,expiresAt,createdAt,"
        + "settledAt,holdTransactionId", lines[0]);
    assertEquals(3, lines.length); // header + the two fixture refunds

    // Newest first, every column in the declaration order above — settledAt
    // stays empty while the network refund is still pending.
    String[] row = lines[1].split(",", -1);
    assertEquals(10, row.length);
    assertEquals(newestRefund.publicId().toString(), row[0]);
    assertEquals(refundableIntent.publicId().toString(), row[1]);
    assertEquals("10.0000", row[2]);
    assertEquals("BRL", row[3]);
    assertEquals("REQUESTED", row[4]);
    assertEquals(newestRefund.networkRefundPublicId().toString(), row[5]);
    assertEquals(newestRefund.expiresAt().toString(), row[6]);
    assertEquals(newestRefund.createdAt().toString(), row[7]);
    assertEquals("", row[8]);
    assertEquals(newestRefund.holdTransactionPublicId().toString(), row[9]);
    assertEquals(firstRefund.publicId().toString(), lines[2].split(",", -1)[0]);
  }

  @Test
  void settledRefundFilterYieldsTheHeaderAlone() throws Exception {
    MvcResult result = mockMvc.perform(get("/v1/refunds")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv")
            .param("status", "SETTLED"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    assertEquals(1, lines.length); // nothing settled: the header row only
    assertEquals("publicId,intentId,amount,currency,status,networkRefundId,expiresAt,createdAt,"
        + "settledAt,holdTransactionId", lines[0]);
  }

  @Test
  void transfersCsvCarriesEveryTransferColumnNewestFirstUnwrapped() throws Exception {
    MvcResult result = mockMvc.perform(get("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.startsWith("attachment; filename=\"transfers-")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    assertEquals("publicId,fromAccountId,toAccountId,amount,currency,journalTransactionId,"
        + "createdAt", lines[0]);
    assertEquals(3, lines.length); // header + the two fixture transfers

    // Newest first, every column in the declaration order above. The journal
    // anchor is the transfer's whole story — its id rides the row raw, and no
    // field here wraps in quotes: separator-bearing values are CsvTest's.
    String[] row = lines[1].split(",", -1);
    assertEquals(7, row.length);
    assertEquals(newestTransfer.publicId().toString(), row[0]);
    assertEquals(accountA.toString(), row[1]);
    assertEquals(accountB.toString(), row[2]);
    assertEquals("2.0000", row[3]);
    assertEquals("BRL", row[4]);
    assertEquals(newestTransfer.journalTransactionPublicId().toString(), row[5]);
    assertEquals(newestTransfer.createdAt().toString(), row[6]);
    assertFalse(lines[1].contains("\""));
    assertEquals(firstTransfer.publicId().toString(), lines[2].split(",", -1)[0]);
  }

  /** The export cap is a cap, not a page: four transfers under
   *  {@code nummus.export.max-rows=3} answer three data rows and say so. */
  @Test
  void transferExportTruncatesAtTheConfiguredRowCap() throws Exception {
    var third = transfers.create(merchantId,
        new CreateTransferCommand(accountA, accountB, Money.ofBrl("1.0000")));
    var newest = transfers.create(merchantId,
        new CreateTransferCommand(accountA, accountB, Money.ofBrl("1.0000")));
    MvcResult result = mockMvc.perform(get("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    assertEquals(5, lines.length); // header + the capped three + the marker
    assertEquals(newest.publicId().toString(), lines[1].split(",", -1)[0]);
    assertEquals(third.publicId().toString(), lines[2].split(",", -1)[0]);
    assertEquals(newestTransfer.publicId().toString(), lines[3].split(",", -1)[0]);
    assertEquals("# truncated: true", lines[4]);
  }

  @Test
  void jsonRemainsTheDefaultRepresentationOnAllThreeListings() throws Exception {
    mockMvc.perform(get("/v1/payouts").header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.length()").value(2));
    mockMvc.perform(get("/v1/refunds").header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.length()").value(2));
    mockMvc.perform(get("/v1/transfers").header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.length()").value(2));
  }

  /**
   * The container is shared across classes and the conciliation suites
   * assert over now-relative windows. Push this class's settlements and
   * network rows two hours back — the same DB-side rewrite the sibling
   * suites use — so they never fall inside another test's window.
   */
  @AfterAll
  static void moveFixturesOutOfNowWindows() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      if (!settledIntents.isEmpty()) {
        st.executeUpdate("UPDATE payments.payment_intent SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledIntents) + ")");
      }
      if (!networkCharges.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge SET updated_at = now() - interval '2 hours'"
          + " WHERE public_id IN (" + quoted(networkCharges) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
