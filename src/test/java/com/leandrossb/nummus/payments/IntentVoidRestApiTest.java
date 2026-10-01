package com.leandrossb.nummus.payments;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;

/**
 * The merchant REST surface for the intent void: POSTing the withdrawal of a
 * CREATED intent returns the VOIDED view and the listing carries it under its
 * own status filter; a pay that wins the charge rejects the void with the
 * observed state while the settle lands lazily on the next read; the stored
 * 200 replays for a same-key retry while a fresh key meets the terminal 409;
 * a foreign intent stays indistinguishable from an unknown one; and the
 * withdrawal leaves its {@code payment_intent.voided} event in the outbox,
 * committed with the transition for the merchant's registered audience.
 */
@AutoConfigureMockMvc
class IntentVoidRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Fixtures this class settles — backdated in {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

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

  /** Voids are merchant-scoped: this class keeps every fixture under the seed
   *  merchant and authenticates each void call with a freshly minted seed key. */
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

  /** A CREATED intent on a freshly opened SeedMerchant account — the void
   *  fixture; the charge starts PENDING on the network. */
  private PaymentIntent createdIntent(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Intent Void Rest Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    networkCharges.add(intent.chargePublicId());
    return intent;
  }

  /** POSTs the void of the intent as the given bearer — the bodyless shape a
   *  withdrawal takes: no payload, the key alone rides the reserve. */
  private ResultActions voidCall(UUID intentPublicId, String bearer, String key) throws Exception {
    return mockMvc.perform(post("/v1/payment-intents/{intentId}/void", intentPublicId)
        .header("Authorization", "Bearer " + bearer)
        .header(KEY, key));
  }

  @Test
  void voidCreatedReturnsTheVoidedViewAndTheListingCarriesIt() throws Exception {
    var intent = createdIntent("40.0000");

    voidCall(intent.publicId(), seedMerchantKey, UUID.randomUUID().toString())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publicId").value(intent.publicId().toString()))
        .andExpect(jsonPath("$.status").value("VOIDED"))
        // A withdrawal never settles: the fact fields stay absent.
        .andExpect(jsonPath("$.settledAt").doesNotExist());

    // The listing carries the withdrawal under its own status filter.
    mockMvc.perform(get("/v1/payment-intents").param("status", "VOIDED")
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].publicId",
            org.hamcrest.Matchers.hasItem(intent.publicId().toString())));
  }

  @Test
  void payThenVoidRejectsWithTheObservedStateAndTheReadSettles() throws Exception {
    var intent = createdIntent("40.0000");
    // The pay wins the race window: the cancel attempt observes SUCCEEDED.
    simulator.pay(intent.chargePublicId());

    voidCall(intent.publicId(), seedMerchantKey, UUID.randomUUID().toString())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail", containsString("SETTLED")));

    // The intent's own state machine completes lazily: the follow-up read
    // settles it, and the money is where the pay left it (seed fee is zero,
    // so the full gross is available).
    mockMvc.perform(get("/v1/payment-intents/{id}", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.settledAt").exists());
    mockMvc.perform(get("/v1/accounts/{id}/balance", intent.accountPublicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(40.0000));
    settledIntents.add(intent.publicId());
  }

  @Test
  void freshKeyMeetsTheTerminalConflictWhileTheSameKeyReplays() throws Exception {
    var intent = createdIntent("40.0000");
    String key = UUID.randomUUID().toString();
    var first = voidCall(intent.publicId(), seedMerchantKey, key)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOIDED"))
        .andReturn();

    // A fresh key reaches the state machine: the terminal row rejects it.
    voidCall(intent.publicId(), seedMerchantKey, UUID.randomUUID().toString())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail", containsString("VOIDED")));

    // The same-key retry never reaches it: the stored 200 replays verbatim.
    var replay = voidCall(intent.publicId(), seedMerchantKey, key)
        .andExpect(status().isOk())
        .andReturn();
    assertTrue(first.getResponse().getHeader("Idempotency-Replayed") == null);
    assertEquals("true", replay.getResponse().getHeader("Idempotency-Replayed"));
    assertEquals(first.getResponse().getContentAsString(),
        replay.getResponse().getContentAsString());
  }

  @Test
  void foreignIntentIsNotFoundAndUntouched() throws Exception {
    var intent = createdIntent("40.0000");
    String other = ApiDrivers.createMerchantAndGetKey(
        mockMvc, operatorAuth(), "Intent Void Rest Other");

    // Ownership scopes the void: another merchant's attempt is
    // indistinguishable from an unknown intent's address.
    voidCall(intent.publicId(), other, UUID.randomUUID().toString())
        .andExpect(status().isNotFound());

    // The rejected attempt left the row exactly as it found it.
    mockMvc.perform(get("/v1/payment-intents/{id}", intent.publicId())
            .header("Authorization", "Bearer " + seedMerchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CREATED"));
  }

  @Test
  void voidedEventLandsInTheOutboxForTheRegisteredAudience() throws Exception {
    var intent = createdIntent("40.0000");
    // Deliverable needs an audience: register an endpoint for the seed merchant.
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + seedMerchantKey)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl("intent-void") + "\"}"))
        .andExpect(status().isCreated());

    voidCall(intent.publicId(), seedMerchantKey, UUID.randomUUID().toString())
        .andExpect(status().isOk());

    // The outbox holds the event, committed with the transition: the payload
    // names the intent it withdrew, already carrying the terminal state.
    try (var c = adminConnection(); var st = c.createStatement()) {
      var rs = st.executeQuery("select payload from webhooks.webhook_event"
          + " where type = 'payment_intent.voided' order by id desc limit 1");
      assertTrue(rs.next());
      String payload = rs.getString("payload");
      assertTrue(payload.contains(intent.publicId().toString()), payload);
      assertTrue(payload.contains("\"status\":\"VOIDED\""), payload);
    }
  }

  /**
   * The container is shared across classes and the conciliation suites
   * assert over now-relative windows. Push this class's settlement and
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
