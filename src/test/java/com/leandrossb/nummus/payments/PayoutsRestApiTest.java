package com.leandrossb.nummus.payments;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The merchant REST surface for payouts: a create that reserves funds under the
 * caller's merchant scope, idempotent replay of the stored response, and the
 * lazy lifecycle driven by GET — mirroring the payment-intent surface.
 */
@AutoConfigureMockMvc
class PayoutsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Fixtures this class settles/charges/transfers — backdated in
   *  {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  private static final List<UUID> payoutIds = new ArrayList<>();

  private static final List<UUID> networkTransfers = new ArrayList<>();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private ApiKeysService apiKeys;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String seedMerchantKey;

  private String operatorAuth;

  /** Payouts are merchant-scoped: this class keeps every fixture under the seed
   *  merchant and authenticates each payout call with a freshly minted seed key. */
  @BeforeEach
  void mintSeedMerchantKey() {
    seedMerchantKey = apiKeys.create(SeedMerchant.PUBLIC_ID, null).secret();
  }

  /** One operator key per class — creating the scoping merchant is operator-gated. */
  private String operatorAuth() {
    if (operatorAuth == null) {
      operatorAuth = ApiDrivers.operatorAuth(operatorKeys);
    }
    return operatorAuth;
  }

  /** A payment account funded by one settled intent — the settle recipe the
   *  payout suites use (create intent, pay the charge, first poll settles). */
  private PaymentAccount fundedAccount(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Payout Rest Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    return account;
  }

  private static String payoutBody(String accountId, String amount, String bankKey) {
    return "{\"accountId\":\"" + accountId + "\",\"amount\":" + amount
        + ",\"destinationBankKey\":\"" + bankKey + "\"}";
  }

  /** POSTs a payout under the seed merchant, expects 201, registers the rows for
   *  the sweep, and returns the Location header. */
  private String createPayout(String accountId, String amount, String bankKey) throws Exception {
    var result = mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payoutBody(accountId, amount, bankKey)))
        .andExpect(status().isCreated())
        .andReturn();
    return register(result);
  }

  private static String register(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    payoutIds.add(UUID.fromString(JsonPath.read(body, "$.publicId")));
    networkTransfers.add(UUID.fromString(JsonPath.read(body, "$.transferId")));
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReservesAndResponds201() throws Exception {
    var account = fundedAccount("100.0000");
    String location = createPayout(account.publicId().toString(), "30.0000", "bank.main-01");

    assertTrue(location.endsWith("/v1/payouts/" + payoutIds.get(payoutIds.size() - 1)));
    mockMvc.perform(get(location).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(account.publicId().toString()))
        .andExpect(jsonPath("$.amount").value(30.0000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.destinationBankKey").value("bank.main-01"))
        .andExpect(jsonPath("$.transferId").exists())
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.reservationTransactionId").exists())
        // The fee is a settled fact, not a quote: nothing is exposed before then.
        .andExpect(jsonPath("$.fee").doesNotExist())
        .andExpect(jsonPath("$.settledAt").doesNotExist());

    // The reservation moved the funds aside: the available balance dropped once.
    mockMvc.perform(get("/v1/accounts/{id}/balance", account.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(70.0000));
  }

  @Test
  void createIsIdempotent() throws Exception {
    var account = fundedAccount("50.0000");
    String body = payoutBody(account.publicId().toString(), "20.0000", "bank.replay-01");
    String key = UUID.randomUUID().toString();

    var first = mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated()).andReturn();
    var retry = mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated()).andReturn();

    assertEquals(first.getResponse().getContentAsString(), retry.getResponse().getContentAsString());
    assertEquals(first.getResponse().getHeader("Location"), retry.getResponse().getHeader("Location"));
    assertTrue(first.getResponse().getHeader("Idempotency-Replayed") == null);
    assertEquals("true", retry.getResponse().getHeader("Idempotency-Replayed"));
    register(first);

    // No double execution: the reservation dropped the balance exactly once.
    mockMvc.perform(get("/v1/accounts/{id}/balance", account.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(30.0000));
  }

  @Test
  void insufficientFundsMapsTo422() throws Exception {
    var account = fundedAccount("5.0000");
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payoutBody(account.publicId().toString(), "30.0000", "bank.reject-01")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail", containsString("insufficient funds")));
  }

  @Test
  void shapeValidationMapsTo400() throws Exception {
    String anyAccount = UUID.randomUUID().toString();
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payoutBody(anyAccount, "5.0000", "   ")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payoutBody(anyAccount, "5.0000", "bank key!")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payoutBody(anyAccount, "0.0000", "bank.zero-01")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + anyAccount + "\",\"amount\":5.0000,"
                + "\"destinationBankKey\":\"bank.ttl-01\",\"expiresInSeconds\":59}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getDrivesTheLifecycleLazily() throws Exception {
    var account = fundedAccount("100.0000");
    String location = createPayout(account.publicId().toString(), "30.0000", "bank.execute-01");
    String requested = mockMvc.perform(get(location).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andReturn().getResponse().getContentAsString();
    UUID transferId = UUID.fromString(JsonPath.read(requested, "$.transferId"));

    simulator.payTransfer(transferId);

    mockMvc.perform(get(location).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"))
        // The settled zero-fee fact, never null and never a quote.
        .andExpect(jsonPath("$.fee").value(0.00))
        .andExpect(jsonPath("$.settledAt").exists())
        .andExpect(jsonPath("$.reservationTransactionId").exists());

    // Ownership scopes the read: another merchant's payout is indistinguishable
    // from an unknown one.
    String other = ApiDrivers.createMerchantAndGetKey(mockMvc, operatorAuth(), "Payout Rest Other");
    mockMvc.perform(get(location).header("Authorization", "Bearer " + other))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/v1/payouts/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isNotFound());
  }

  @Test
  void noAuthIs401AndMerchantScopingHolds() throws Exception {
    // Neither an API key nor an Idempotency-Key: 401 (auth), not 400 (idempotency).
    mockMvc.perform(post("/v1/payouts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payoutBody(UUID.randomUUID().toString(), "5.0000", "bank.anon-01")))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/payouts/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  /**
   * The container is shared across classes and the conciliation suites assert
   * over now-relative windows. Push this class's settlements, network rows, and
   * payout fixtures two hours back — the same DB-side rewrite the sibling
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
      if (!payoutIds.isEmpty()) {
        st.executeUpdate("UPDATE payments.payout SET created_at = now() - interval '2 hours',"
            + " expires_at = expires_at - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(payoutIds) + ")");
        st.executeUpdate("UPDATE payments.payout SET settled_at = settled_at - interval '2 hours'"
            + " WHERE settled_at IS NOT NULL AND public_id IN (" + quoted(payoutIds) + ")");
      }
      if (!networkTransfers.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.payout_transfer"
            + " SET created_at = now() - interval '2 hours', updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(networkTransfers) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
