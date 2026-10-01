package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.payments.application.PaymentsRepository;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PaymentsServiceImpl;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentNotVoidableException;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The void state machine over the real context: one cancel attempt on the
 * network, then the branch on the post-attempt charge — a cancelled charge
 * voids, a parallel pay settles and rejects, a failed charge fails and
 * rejects. Every collaborator is the real bean (simulator, ledger, Postgres
 * repository) except the publisher: the outbox joins the caller's
 * transaction, which this hand-composed service deliberately opens none of,
 * so the no-op publisher the unit suites use stands in — event delivery is
 * Task-3 scope. The class's own {@link SimpleMeterRegistry} keeps each
 * test's lifecycle counters at zero (the RefundsServiceImplTest pin
 * precedent). Service-level on purpose — the HTTP surface arrives with
 * Task 3.
 */
class IntentVoidServiceTest extends IntegrationTestBase {

  /** Fixtures this class settles — backdated in {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentNetwork network;

  @Autowired
  private PaymentsRepository repository;

  @Autowired
  private MerchantsService merchants;

  @Autowired
  private Ledger ledger;

  @Autowired
  private SimulatorService simulator;

  /** The class's own registry: per-test counters, not the shared context's. */
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private PaymentsService payments;

  @BeforeEach
  void composePaymentsService() {
    payments = new PaymentsServiceImpl(ledger, accountsService, network, repository,
        event -> { }, merchants, registry);
  }

  /**
   * A CREATED intent on a freshly opened SeedMerchant account — the sibling
   * service suites' fixture. SeedMerchant carries the zero fee schedule, so
   * a settle lands the full gross as available balance.
   */
  private PaymentIntent createdIntent(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Intent Void Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    networkCharges.add(intent.chargePublicId());
    return intent;
  }

  @Test
  void voidingACreatedIntentCancelsTheChargeAndCounts() {
    var intent = createdIntent("40.0000");

    var voided = payments.voidIntent(SeedMerchant.PUBLIC_ID, intent.publicId());

    assertEquals(IntentStatus.VOIDED, voided.status());
    assertEquals(ChargeStatus.CANCELLED, simulator.get(intent.chargePublicId()).status());
    assertEquals(1.0,
        registry.get("nummus.intents").tag("outcome", "voided").counter().count());
  }

  @Test
  void voidAfterAParallelPaySettlesAndRejectsWithTheTruth() {
    var intent = createdIntent("40.0000");
    // The pay wins the race window: the cancel attempt observes SUCCEEDED.
    simulator.pay(intent.chargePublicId());

    var rejected = assertThrows(IntentNotVoidableException.class,
        () -> payments.voidIntent(SeedMerchant.PUBLIC_ID, intent.publicId()));

    assertEquals(IntentStatus.SETTLED, rejected.current());
    // Money already moved: the intent really is SETTLED and the settled
    // balance is intact — the read after the pay settles what the void
    // refused to withdraw.
    var settled = payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    assertEquals(IntentStatus.SETTLED, settled.status());
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, intent.accountPublicId())
        .compareTo(Money.ofBrl("40.0000")));
    settledIntents.add(intent.publicId());
  }

  @Test
  void voidAfterAFailureFailsTheIntentAndRejects() {
    var intent = createdIntent("40.0000");
    simulator.fail(intent.chargePublicId());

    var rejected = assertThrows(IntentNotVoidableException.class,
        () -> payments.voidIntent(SeedMerchant.PUBLIC_ID, intent.publicId()));

    assertEquals(IntentStatus.FAILED, rejected.current());
    assertEquals(IntentStatus.FAILED,
        payments.get(SeedMerchant.PUBLIC_ID, intent.publicId()).status());
  }

  @Test
  void voidingAnAlreadyVoidedIntentRejectsWithVoided() {
    var intent = createdIntent("40.0000");
    payments.voidIntent(SeedMerchant.PUBLIC_ID, intent.publicId());

    var rejected = assertThrows(IntentNotVoidableException.class,
        () -> payments.voidIntent(SeedMerchant.PUBLIC_ID, intent.publicId()));

    assertEquals(IntentStatus.VOIDED, rejected.current());
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
