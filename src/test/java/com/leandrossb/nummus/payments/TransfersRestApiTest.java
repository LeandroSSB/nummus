package com.leandrossb.nummus.payments;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.TransfersService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateTransferCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The merchant REST surface for internal transfers: a create that moves booked
 * funds between two of the caller's own accounts in one balanced entry — the
 * response IS the completed fact, no lifecycle for a GET to drive — idempotent
 * replay of the stored response, ownership-masked reads, the newest-first
 * keyset listing, and the completion event riding the same commit in the
 * outbox for the merchant's registered audience.
 */
@AutoConfigureMockMvc
class TransfersRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Fixtures this class settles/charges — backdated in
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
  private TransfersService transfers;

  @Autowired
  private SimulatorService simulator;

  private UUID merchantId;

  private String merchantKey;

  /** A fresh merchant per test over HTTP, operator-authenticated — the listing
   *  sibling's fixture: the walk asserts exact counts, so no other test's
   *  transfers may surface. The default schedule prices nothing, so a settle
   *  lands the full gross as available balance. */
  @BeforeEach
  void createFixtures() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Transfers Rest Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
  }

  /** A payment account funded by one settled intent — the settle recipe the
   *  transfer suites use (create intent, pay the charge, first poll settles). */
  private PaymentAccount fundedAccount(String amount, String label) {
    var account = accountsService.open(merchantId, new OpenAccountCommand(label));
    var intent = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(merchantId, intent.publicId());
    return account;
  }

  private PaymentAccount fundedFromAccount(String amount) {
    return fundedAccount(amount, "Transfers Rest From");
  }

  /** The transfer's other end: an open account of the same merchant. */
  private PaymentAccount targetAccount() {
    return accountsService.open(merchantId, new OpenAccountCommand("Transfers Rest To"));
  }

  private static String transferBody(String fromAccountId, String toAccountId, String amount) {
    return "{\"fromAccountId\":\"" + fromAccountId + "\",\"toAccountId\":\"" + toAccountId
        + "\",\"amount\":" + amount + "}";
  }

  /** POSTs a transfer as the fixture merchant, expects 201, and returns the
   *  result for whatever the caller reads out of it. */
  private MvcResult createTransfer(String fromAccountId, String toAccountId, String amount)
      throws Exception {
    return mockMvc.perform(post("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(transferBody(fromAccountId, toAccountId, amount)))
        .andExpect(status().isCreated())
        .andReturn();
  }

  /** The fixture merchant's available-balance read — the M21 surface. */
  private ResultActions balanceRead(UUID accountPublicId) throws Exception {
    return mockMvc.perform(get("/v1/accounts/{id}/balance", accountPublicId)
        .header("Authorization", "Bearer " + merchantKey));
  }

  private static CreateTransferCommand command(PaymentAccount from, PaymentAccount to,
      String amount) {
    return new CreateTransferCommand(from.publicId(), to.publicId(), Money.ofBrl(amount));
  }

  /** Walks the listing one page at a time, returning every public id in visit
   *  order — and pinning that no cursor ever repeats. */
  private List<String> walk(int limit) throws Exception {
    Set<String> cursors = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/transfers" + query)
              .header("Authorization", "Bearer " + merchantKey))
          .andExpect(status().isOk()).andReturn();
      ids.addAll(JsonPath.<List<String>>read(result.getResponse().getContentAsString(),
          "$[*].publicId"));
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=" + limit + "&after=" + cursor;
      if (cursor != null) {
        cursors.add(cursor);
      }
    }
    assertEquals(pages - 1, cursors.size());
    return ids;
  }

  @Test
  void createReturns201WithTheTransferViewAndMovesBothBalances() throws Exception {
    var from = fundedFromAccount("100.0000");
    var to = targetAccount();

    MvcResult result = createTransfer(from.publicId().toString(), to.publicId().toString(),
        "30.0000");
    String body = result.getResponse().getContentAsString();
    UUID transferId = UUID.fromString(JsonPath.read(body, "$.publicId"));
    String location = result.getResponse().getHeader("Location");
    assertTrue(location.endsWith("/v1/transfers/" + transferId), location);

    // The create IS the completed fact: every field present, none pending.
    mockMvc.perform(get(location).header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publicId").value(transferId.toString()))
        .andExpect(jsonPath("$.fromAccountId").value(from.publicId().toString()))
        .andExpect(jsonPath("$.toAccountId").value(to.publicId().toString()))
        .andExpect(jsonPath("$.amount").value(30.0000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.journalTransactionId").exists())
        .andExpect(jsonPath("$.createdAt").exists());

    // Booked funds moved between the merchant's own accounts, both directions.
    balanceRead(from.publicId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(70.0000));
    balanceRead(to.publicId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(30.0000));
  }

  @Test
  void createIsIdempotent() throws Exception {
    var from = fundedFromAccount("50.0000");
    var to = targetAccount();
    String body = transferBody(from.publicId().toString(), to.publicId().toString(), "20.0000");
    String key = UUID.randomUUID().toString();

    var first = mockMvc.perform(post("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated()).andReturn();
    var retry = mockMvc.perform(post("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated()).andReturn();

    assertEquals(first.getResponse().getContentAsString(), retry.getResponse().getContentAsString());
    assertEquals(first.getResponse().getHeader("Location"), retry.getResponse().getHeader("Location"));
    assertTrue(first.getResponse().getHeader("Idempotency-Replayed") == null);
    assertEquals("true", retry.getResponse().getHeader("Idempotency-Replayed"));

    // No double execution: the funds moved exactly once, on each side.
    balanceRead(from.publicId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(30.0000));
    balanceRead(to.publicId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(20.0000));
  }

  @Test
  void foreignTransferIsNotFound() throws Exception {
    var from = fundedFromAccount("40.0000");
    var to = targetAccount();
    String location = createTransfer(from.publicId().toString(), to.publicId().toString(),
        "10.0000").getResponse().getHeader("Location");
    mockMvc.perform(get(location).header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk());

    // Ownership scopes the read: another merchant's transfer is indistinguishable
    // from an unknown one.
    String other = ApiDrivers.createMerchantAndGetKey(mockMvc,
        ApiDrivers.operatorAuth(operatorKeys), "Transfers Rest Other");
    mockMvc.perform(get(location).header("Authorization", "Bearer " + other))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/v1/transfers/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isNotFound());
  }

  /** The merchant's transfers, newest first, chained by Next-Cursor — every
   *  cursor distinct, every row exactly once — and a cursor that names no
   *  transfer at all yields the empty page: nothing to walk below it. */
  @Test
  void listingWalksNewestFirstExactlyOnceAndAnUnknownCursorIsEmpty() throws Exception {
    var from = fundedFromAccount("90.0000");
    var to = targetAccount();
    transfers.create(merchantId, command(from, to, "30.0000"));
    transfers.create(merchantId, command(from, to, "30.0000"));
    var newest = transfers.create(merchantId, command(from, to, "30.0000"));

    var ids = walk(2);
    assertEquals(3, ids.size());
    assertEquals(3, new HashSet<>(ids).size());
    assertEquals(newest.publicId().toString(), ids.get(0));

    mockMvc.perform(get("/v1/transfers?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shapeValidationMapsTo400() throws Exception {
    String anyAccount = UUID.randomUUID().toString();
    // amount 0 — the positive bound.
    mockMvc.perform(post("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(transferBody(anyAccount, UUID.randomUUID().toString(), "0.0000")))
        .andExpect(status().isBadRequest());
    // A missing toAccountId never validates: the other end is mandatory.
    mockMvc.perform(post("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fromAccountId\":\"" + anyAccount + "\",\"amount\":5.0000}"))
        .andExpect(status().isBadRequest());
    // The same account as both ends: the service's IllegalArgumentException
    // surfaces as 400 — the family every sibling route maps it to.
    var account = accountsService.open(merchantId, new OpenAccountCommand("Transfers Rest Loop"));
    mockMvc.perform(post("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(transferBody(account.publicId().toString(), account.publicId().toString(),
                "5.0000")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("must differ")));
  }

  @Test
  void insufficientFundsMapsTo422AndFrozenFromAccountTo409() throws Exception {
    var to = targetAccount();
    var poor = fundedFromAccount("5.0000");
    mockMvc.perform(post("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(transferBody(poor.publicId().toString(), to.publicId().toString(),
                "30.0000")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail", containsString("insufficient funds")));

    // The freeze guard applies to internal moves too: non-ACTIVE is a conflict.
    var frozen = fundedFromAccount("50.0000");
    accountsService.freeze(merchantId, frozen.publicId());
    mockMvc.perform(post("/v1/transfers")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(transferBody(frozen.publicId().toString(), to.publicId().toString(),
                "10.0000")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail", containsString("is CLOSED")));
  }

  @Test
  void completedEventLandsInTheOutboxForTheRegisteredAudience() throws Exception {
    var from = fundedFromAccount("25.0000");
    var to = targetAccount();
    // Deliverable needs an audience — one scoped to exactly transfer.completed:
    // the 201 itself is the catalog pin (a type outside the merchant catalog
    // answers 400), and the probe below then rides selective fan-out rather
    // than subscribe-all.
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("transfer-completed")
                + "\",\"eventTypes\":[\"transfer.completed\"]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.eventTypes[0]").value("transfer.completed"));

    String body = createTransfer(from.publicId().toString(), to.publicId().toString(), "25.0000")
        .getResponse().getContentAsString();
    UUID transferId = UUID.fromString(JsonPath.read(body, "$.publicId"));
    UUID journalTransactionId = UUID.fromString(JsonPath.read(body, "$.journalTransactionId"));

    // The outbox holds the event, committed with the transfer: the payload
    // names the transfer and carries the journal link — the record itself.
    try (var c = adminConnection(); var st = c.createStatement()) {
      var rs = st.executeQuery("select payload from webhooks.webhook_event"
          + " where type = 'transfer.completed' order by id desc limit 1");
      assertTrue(rs.next());
      String payload = rs.getString("payload");
      assertTrue(payload.contains("\"" + transferId + "\""), payload);
      assertTrue(payload.contains(
          "\"journalTransactionId\":\"" + journalTransactionId + "\""), payload);
    }
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
