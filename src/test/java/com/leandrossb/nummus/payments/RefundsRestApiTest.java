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
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
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
 * The merchant REST surface for refunds: a create that holds the exact amount
 * under the caller's merchant scope, idempotent replay of the stored response,
 * and the lazy lifecycle driven by GET — mirroring the payout surface. The
 * intent view carries the refunded total: the sum a later request is capped
 * against, FAILED and EXPIRED refunds released.
 */
@AutoConfigureMockMvc
class RefundsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Fixtures this class settles/charges/refunds — backdated in
   *  {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  private static final List<UUID> refundIds = new ArrayList<>();

  private static final List<UUID> networkRefunds = new ArrayList<>();

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

  /** Refunds are merchant-scoped: this class keeps every fixture under the seed
   *  merchant and authenticates each refund call with a freshly minted seed key. */
  @BeforeEach
  void mintSeedMerchantKey() {
    seedMerchantKey = apiKeys.create(SeedMerchant.PUBLIC_ID, null).secret();
  }

  /** One operator key per class — creating the scoping merchant is operator-gated. */
  private String operatorAuth() {
    if (operatorAuthValue == null) {
      operatorAuthValue = ApiDrivers.operatorAuth(operatorKeys);
    }
    return operatorAuthValue;
  }

  private String operatorAuthValue;

  /** A payment account funded by one settled intent — the settle recipe the
   *  refund suites use (create intent, pay the charge, first poll settles). */
  private PaymentIntent settledIntent(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Refund Rest Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    return payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
  }

  private static String refundBody(String amount) {
    return "{\"amount\":" + amount + "}";
  }

  /** POSTs a refund of the intent under the seed merchant, expects 201,
   *  registers the rows for the sweep, and returns the Location header. */
  private String createRefund(UUID intentPublicId, String amount) throws Exception {
    var result = mockMvc.perform(post("/v1/payment-intents/{intentId}/refunds", intentPublicId)
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(refundBody(amount)))
        .andExpect(status().isCreated())
        .andReturn();
    return register(result);
  }

  private static String register(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    refundIds.add(UUID.fromString(JsonPath.read(body, "$.publicId")));
    networkRefunds.add(UUID.fromString(JsonPath.read(body, "$.networkRefundId")));
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReturns201WithTheRefundView() throws Exception {
    var intent = settledIntent("100.0000");
    String location = createRefund(intent.publicId(), "30.0000");

    assertTrue(location.endsWith("/v1/refunds/" + refundIds.get(refundIds.size() - 1)));
    mockMvc.perform(get(location).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.intentId").value(intent.publicId().toString()))
        .andExpect(jsonPath("$.amount").value(30.0000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.networkRefundId").exists())
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.holdTransactionId").exists())
        // The settled fact appears post-settlement only.
        .andExpect(jsonPath("$.settledAt").doesNotExist());

    // The hold moved the funds aside: the available balance dropped once.
    mockMvc.perform(get("/v1/accounts/{id}/balance", intent.accountPublicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(70.0000));
  }

  @Test
  void createIsIdempotent() throws Exception {
    var intent = settledIntent("50.0000");
    String body = refundBody("20.0000");
    String key = UUID.randomUUID().toString();

    var first = mockMvc.perform(post("/v1/payment-intents/{intentId}/refunds", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated()).andReturn();
    var retry = mockMvc.perform(post("/v1/payment-intents/{intentId}/refunds", intent.publicId())
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

    // No double execution: the hold dropped the balance exactly once...
    mockMvc.perform(get("/v1/accounts/{id}/balance", intent.accountPublicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(30.0000));
    // ...and the intent's refunded total accounts the refund exactly once.
    mockMvc.perform(get("/v1/payment-intents/{id}", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundedTotal").value(20.0000));
  }

  @Test
  void exceedingRemainingMapsTo422() throws Exception {
    var intent = settledIntent("100.0000");
    createRefund(intent.publicId(), "100.0000");

    mockMvc.perform(post("/v1/payment-intents/{intentId}/refunds", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(refundBody("0.0100")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail", containsString("exceeds the remaining amount")));
  }

  @Test
  void notSettledMapsTo409AndShapeTo400() throws Exception {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Refund Rest Status Merchant"));
    var created = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("10.0000"), null));
    networkCharges.add(created.chargePublicId());

    mockMvc.perform(post("/v1/payment-intents/{intentId}/refunds", created.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(refundBody("1.0000")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail", containsString("not refundable")));
    mockMvc.perform(post("/v1/payment-intents/{intentId}/refunds", created.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(refundBody("0.0000")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/payment-intents/{intentId}/refunds", created.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":1.0000,\"expiresInSeconds\":59}"))
        .andExpect(status().isBadRequest());

    // An open intent exposes the sum like a settled one: zero until refunded.
    mockMvc.perform(get("/v1/payment-intents/{id}", created.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundedTotal").value(0));
  }

  @Test
  void getDrivesTheLifecycleLazilyAndScopes() throws Exception {
    var intent = settledIntent("100.0000");
    String location = createRefund(intent.publicId(), "30.0000");
    String requested = mockMvc.perform(get(location).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andReturn().getResponse().getContentAsString();
    UUID networkRefundId = UUID.fromString(JsonPath.read(requested, "$.networkRefundId"));

    simulator.payRefund(networkRefundId);

    mockMvc.perform(get(location).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.settledAt").exists())
        .andExpect(jsonPath("$.holdTransactionId").exists());

    // Ownership scopes the read: another merchant's refund is indistinguishable
    // from an unknown one.
    String other = ApiDrivers.createMerchantAndGetKey(mockMvc, operatorAuth(), "Refund Rest Other");
    mockMvc.perform(get(location).header("Authorization", "Bearer " + other))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/v1/refunds/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isNotFound());

    // The refund routes are merchant-protected: no bearer is 401 (auth), not a
    // later 400 (idempotency) — including the bare /v1/refunds prefix.
    mockMvc.perform(get("/v1/refunds/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/v1/payment-intents/{intentId}/refunds", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(refundBody("1.0000")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void intentExposesRefundedTotal() throws Exception {
    var intent = settledIntent("100.0000");

    createRefund(intent.publicId(), "30.0000");
    mockMvc.perform(get("/v1/payment-intents/{id}", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundedTotal").value(30.0000));

    String secondLocation = createRefund(intent.publicId(), "20.0000");
    mockMvc.perform(get("/v1/payment-intents/{id}", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundedTotal").value(50.0000));

    // A FAILED refund releases what it held: it drops out of the sum. The lazy
    // read drives the failure; the intent's total falls back to the survivor.
    String second = mockMvc.perform(get(secondLocation).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    simulator.failRefund(UUID.fromString(JsonPath.read(second, "$.networkRefundId")));
    mockMvc.perform(get(secondLocation).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
    mockMvc.perform(get("/v1/payment-intents/{id}", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundedTotal").value(30.0000));
  }

  /**
   * The M17 strand, inverted by M18's expiry resolution: the expired read no
   * longer abandons the network instruction — the resolver withdraws it, so
   * the network releases its remainder alongside the payments-side hold.
   * Through M17 this test pinned the documented backlog bound with a 422 (the
   * abandoned instruction stayed PENDING and kept holding the charge's
   * remainder); with CANCELLED terminal and off the network cap that bound is
   * dead — a fresh full refund now succeeds and settles end-to-end. The
   * network-side 422 mapping loses its only HTTP exercise with it: the
   * payments/network divergence it needed is no longer constructible through
   * the merchant surface, and the cap itself stays pinned at the port
   * (RefundNetworkTest.networkRejectsOverRefund).
   */
  @Test
  void fullRefundExpiredThenCancelledReleasesBothSides() throws Exception {
    var intent = settledIntent("100.0000");
    String location = createRefund(intent.publicId(), "100.0000");
    String requested = mockMvc.perform(get(location).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    UUID refundId = UUID.fromString(JsonPath.read(requested, "$.publicId"));

    // The only way a refund ages past its expiry in-test: backdate the row.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.refund SET expires_at = now() - interval '1 second'"
          + " WHERE public_id = '" + refundId + "'");
    }
    mockMvc.perform(get(location).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
    // The hold returned and the sum dropped: payments-side, all clear again.
    mockMvc.perform(get("/v1/accounts/{id}/balance", intent.accountPublicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(100.0000));
    mockMvc.perform(get("/v1/payment-intents/{id}", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundedTotal").value(0));

    // The network side cleared too — the resolver cancelled the stranded
    // instruction, so the full amount is refundable again: 201, not the 422
    // this pin asserted through M17.
    String freshLocation = createRefund(intent.publicId(), "100.0000");
    String fresh = mockMvc.perform(get(freshLocation)
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andReturn().getResponse().getContentAsString();
    simulator.payRefund(UUID.fromString(JsonPath.read(fresh, "$.networkRefundId")));
    mockMvc.perform(get(freshLocation).header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    mockMvc.perform(get("/v1/payment-intents/{id}", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundedTotal").value(100.0000));
  }

  /**
   * The container is shared across classes and the conciliation suites assert
   * over now-relative windows. Push this class's settlements, network rows, and
   * refund fixtures two hours back — the same DB-side rewrite the sibling
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
      if (!refundIds.isEmpty()) {
        st.executeUpdate("UPDATE payments.refund SET created_at = now() - interval '2 hours',"
            + " expires_at = expires_at - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(refundIds) + ")");
        st.executeUpdate("UPDATE payments.refund SET settled_at = settled_at - interval '2 hours'"
            + " WHERE settled_at IS NOT NULL AND public_id IN (" + quoted(refundIds) + ")");
      }
      if (!networkRefunds.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge_refund"
            + " SET created_at = now() - interval '2 hours', updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(networkRefunds) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
