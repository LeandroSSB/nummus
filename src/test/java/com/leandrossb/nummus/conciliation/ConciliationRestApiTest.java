package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ConciliationRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Fixtures this class settles/charges — backdated in {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  private static final List<UUID> settledPayouts = new ArrayList<>();

  private static final List<UUID> paidTransfers = new ArrayList<>();

  private static final List<UUID> settledRefunds = new ArrayList<>();

  private static final List<UUID> paidNetworkRefunds = new ArrayList<>();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth;

  /** One operator key per test — conciliation is operator-gated. */
  private String operatorAuth() {
    if (operatorAuth == null) {
      operatorAuth = ApiDrivers.operatorAuth(operatorKeys);
    }
    return operatorAuth;
  }

  private String ingest(String from, String to) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  /** Funds a fresh account with a settled 1000 charge, pays `amount` out, and
   *  reads the payout to settlement. Registers every fixture for the sweep. */
  private Payout settlePayout(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Concile Payout Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1000.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var payout = payouts.create(SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl(amount), "bank-key-1", null));
    simulator.payTransfer(payout.transferPublicId());
    paidTransfers.add(payout.transferPublicId());
    var settled = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());
    settledPayouts.add(settled.publicId());
    return settled;
  }

  /** The lazy-noise shape: the transfer executes, nobody ever reads the
   *  payout, so the books never post the settlement. */
  private Payout settlePayoutUnread(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Unread Payout Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1000.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var payout = payouts.create(SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl(amount), "bank-key-1", null));
    simulator.payTransfer(payout.transferPublicId());
    paidTransfers.add(payout.transferPublicId());
    return payout;
  }

  /** Settles a fresh 50 intent, refunds `amount`, and reads the refund to
   *  settlement. Registers every fixture for the sweep. */
  private Refund settleRefund(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Concile Refund Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("50.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var refund = refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl(amount), null));
    simulator.payRefund(refund.networkRefundPublicId());
    paidNetworkRefunds.add(refund.networkRefundPublicId());
    var settled = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());
    settledRefunds.add(settled.publicId());
    return settled;
  }

  /** The refund flavor of the lazy-noise shape: network side executed, the
   *  refund row is never read. */
  private Refund settleRefundUnread(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Unread Refund Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("50.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var refund = refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl(amount), null));
    simulator.payRefund(refund.networkRefundPublicId());
    paidNetworkRefunds.add(refund.networkRefundPublicId());
    return refund;
  }

  @Test
  void settledIntentsConcileAndReplayIdempotently() throws Exception {
    // Scoped window: everything this test settles lands after `start`, and the
    // 30s back-margin absorbs DB-lag on the simulator's `updated_at` (DB clock)
    // relative to this JVM clock. Other classes' now-window fixtures cannot
    // leak in, whatever order JUnit runs methods or classes in.
    Instant start = Instant.now();
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Concile Merchant"));
    var first = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("11.0000"), null));
    var second = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("12.0000"), null));
    settledIntents.add(first.publicId());
    settledIntents.add(second.publicId());
    networkCharges.add(first.chargePublicId());
    networkCharges.add(second.chargePublicId());
    simulator.pay(first.chargePublicId());
    simulator.pay(second.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, first.publicId());
    payments.get(SeedMerchant.PUBLIC_ID, second.publicId());

    String from = start.minusSeconds(30).toString();
    String to = Instant.now().plusSeconds(60).toString();
    String body = "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    var created = mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CONCILED"))
        .andExpect(jsonPath("$.matched").value(2))
        .andExpect(jsonPath("$.missingExternal").value(0))
        .andReturn().getResponse().getContentAsString();
    String reportId = com.jayway.jsonpath.JsonPath.read(created, "$.reportId");

    // A fresh key executes a fresh ingest; replaying THAT key returns its stored
    // response verbatim (idempotency is key-scoped, not body-scoped).
    String replayKey = UUID.randomUUID().toString();
    String executed = mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth()).header(KEY, replayKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String executedReportId = com.jayway.jsonpath.JsonPath.read(executed, "$.reportId");
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth()).header(KEY, replayKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.reportId").value(executedReportId));

    mockMvc.perform(get("/v1/conciliation/reports").header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].reportId").value(executedReportId));
    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MATCHED')]").isNotEmpty());
  }

  @Test
  void divergencesSurfacePerLine() throws Exception {
    // Same scoping as the happy path: only fixtures created from here on are
    // asserted, so an earlier method's settlements may add MATCHED lines but
    // cannot fabricate or hide this test's divergences.
    Instant start = Instant.now();
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Divergence Merchant"));
    // MISSING_INTERNAL: the network settled a charge no intent knows about.
    var orphan = simulator.create(Money.ofBrl("77.0000"));
    networkCharges.add(orphan.publicId());
    simulator.pay(orphan.publicId());
    // AMOUNT_MISMATCH: settle internally, then tamper the network's amount DB-side.
    var tampered = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("20.0000"), null));
    settledIntents.add(tampered.publicId());
    networkCharges.add(tampered.chargePublicId());
    simulator.pay(tampered.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, tampered.publicId());
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.charge SET amount = amount + 1"
          + " WHERE public_id = '" + tampered.chargePublicId() + "'");
    }
    // MISSING_EXTERNAL: the intent settles inside the window, but the network's
    // line for its charge sits outside the report window (DB-side rewrite of the
    // charge's settlement timestamp only — the intent's settled_at stays now).
    var excluded = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("30.0000"), null));
    settledIntents.add(excluded.publicId());
    networkCharges.add(excluded.chargePublicId());
    simulator.pay(excluded.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, excluded.publicId());
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.charge SET updated_at = now() - interval '2 hours'"
          + " WHERE public_id = '" + excluded.chargePublicId() + "'");
    }

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MISSING_INTERNAL')]").isNotEmpty())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'AMOUNT_MISMATCH')]").isNotEmpty())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MISSING_EXTERNAL')]").isNotEmpty());
  }

  @Test
  void settledPayoutsAndRefundsConcileUnderTheirKinds() throws Exception {
    Instant start = Instant.now();
    var payout = settlePayout("30.0000");
    var refund = settleRefund("10.0000");

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'PAYOUT_TRANSFER' && @.matchStatus == 'MATCHED' && @.subjectId == '"
                + payout.transferPublicId() + "')]").isNotEmpty())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'CHARGE_REFUND' && @.matchStatus == 'MATCHED' && @.subjectId == '"
                + refund.networkRefundPublicId() + "')]").isNotEmpty());
  }

  @Test
  void executedButUnreadPayoutIsMissingInternal() throws Exception {
    Instant start = Instant.now();
    var payout = settlePayoutUnread("25.0000"); // payTransfer, then NEVER payouts.get

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'PAYOUT_TRANSFER' && @.matchStatus == 'MISSING_INTERNAL' && @.subjectId == '"
                + payout.transferPublicId() + "')]").isNotEmpty());
  }

  @Test
  void payoutAmountMismatchAndMissingExternalSurface() throws Exception {
    Instant start = Instant.now();
    var tampered = settlePayout("20.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.payout_transfer SET amount = amount + 1"
          + " WHERE public_id = '" + tampered.transferPublicId() + "'");
    }
    var excluded = settlePayout("21.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.payout_transfer SET updated_at = now() - interval '2 hours'"
          + " WHERE public_id = '" + excluded.transferPublicId() + "'");
    }

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'PAYOUT_TRANSFER' && @.matchStatus == 'AMOUNT_MISMATCH' && @.subjectId == '"
                + tampered.transferPublicId() + "')]").isNotEmpty())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'PAYOUT_TRANSFER' && @.matchStatus == 'MISSING_EXTERNAL' && @.subjectId == '"
                + excluded.transferPublicId() + "')]").isNotEmpty());
  }

  @Test
  void refundDivergencesMirrorPayouts() throws Exception {
    Instant start = Instant.now();
    var unread = settleRefundUnread("11.0000");   // payRefund, then NEVER refunds.get
    var tampered = settleRefund("12.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.charge_refund SET amount = amount + 1"
          + " WHERE public_id = '" + tampered.networkRefundPublicId() + "'");
    }

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'CHARGE_REFUND' && @.matchStatus == 'MISSING_INTERNAL' && @.subjectId == '"
                + unread.networkRefundPublicId() + "')]").isNotEmpty())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'CHARGE_REFUND' && @.matchStatus == 'AMOUNT_MISMATCH' && @.subjectId == '"
                + tampered.networkRefundPublicId() + "')]").isNotEmpty());
  }

  @Test
  void unknownReportIs404AndInvertedWindowIs400() throws Exception {
    mockMvc.perform(get("/v1/conciliation/reports/" + UUID.randomUUID())
            .header("Authorization", operatorAuth()))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"2026-09-18T11:00:00Z\",\"to\":\"2026-09-18T10:00:00Z\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"not-an-instant\",\"to\":\"2026-09-18T11:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postRequiresAnIdempotencyKey() throws Exception {
    // Authenticated as operator, but no Idempotency-Key: the idempotency 400.
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"2026-09-18T10:00:00Z\",\"to\":\"2026-09-18T11:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * The container is shared across classes and later suites (settlement query,
   * simulator report) assert over now-relative windows. Push this class's
   * settlements and network charges two hours back — the same DB-side rewrite
   * the divergence setup uses — so they never fall inside another test's window.
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
      if (!settledPayouts.isEmpty()) {
        st.executeUpdate("UPDATE payments.payout SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledPayouts) + ")");
      }
      if (!paidTransfers.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.payout_transfer SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(paidTransfers) + ")");
      }
      if (!settledRefunds.isEmpty()) {
        st.executeUpdate("UPDATE payments.refund SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledRefunds) + ")");
      }
      if (!paidNetworkRefunds.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge_refund SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(paidNetworkRefunds) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
