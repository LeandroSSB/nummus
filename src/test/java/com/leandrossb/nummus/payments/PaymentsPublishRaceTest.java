package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.application.AccountsServiceImpl;
import com.leandrossb.nummus.accounts.application.InMemoryAccountsRepository;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.InMemoryLedgerRepository;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.LedgerServiceImpl;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.FakePaymentNetwork;
import com.leandrossb.nummus.payments.application.IntentEventTypes;
import com.leandrossb.nummus.payments.application.IntentLifecycleEvent;
import com.leandrossb.nummus.payments.application.IntentLifecycleEvents;
import com.leandrossb.nummus.payments.application.InMemoryPaymentsRepository;
import com.leandrossb.nummus.payments.application.PaymentsRepository;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PaymentsServiceImpl;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A lost transition race publishes nothing: the winner already published the
 * event for the terminal state, so the loser just re-reads and returns it.
 * (The SETTLED race is pinned by M3's ConcurrentSettlementException test.)
 */
class PaymentsPublishRaceTest {

  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());
  private final AccountsService accounts =
      new AccountsServiceImpl(ledger, new InMemoryAccountsRepository());
  private final FakePaymentNetwork network = new FakePaymentNetwork();
  private final RecordingIntentEvents intentEvents = new RecordingIntentEvents();

  @Test
  void lostExpireRacePublishesNothing() {
    var repo = new LostTransitionRepository(IntentStatus.SETTLED);
    var payments = paymentsWith(repo);
    var intent = createdIntent(payments);
    repo.agePastExpiry(intent.publicId());

    var observed = payments.get(intent.publicId());

    // The loser sees the winner's committed terminal state and stays silent.
    assertEquals(IntentStatus.SETTLED, observed.status());
    assertTrue(intentEvents.published.isEmpty(), "a lost race must publish nothing");
  }

  @Test
  void lostFailRacePublishesNothing() {
    var repo = new LostTransitionRepository(IntentStatus.EXPIRED);
    var payments = paymentsWith(repo);
    var intent = createdIntent(payments);
    network.fail(intent.chargePublicId());

    var observed = payments.get(intent.publicId());

    // The loser sees the winner's committed terminal state and stays silent.
    assertEquals(IntentStatus.EXPIRED, observed.status());
    assertTrue(intentEvents.published.isEmpty(), "a lost race must publish nothing");
  }

  @Test
  void wonExpireTransitionPublishesExactlyOnce() {
    var repo = new InMemoryPaymentsRepository();
    var payments = paymentsWith(repo);
    var intent = createdIntent(payments);
    repo.agePastExpiry(intent.publicId());

    var expired = payments.get(intent.publicId());

    assertEquals(IntentStatus.EXPIRED, expired.status());
    assertEquals(1, intentEvents.published.size());
    assertEquals(IntentEventTypes.EXPIRED, intentEvents.published.get(0).type());
    assertEquals(intent.publicId(), intentEvents.published.get(0).publicId());
  }

  private PaymentsService paymentsWith(PaymentsRepository repository) {
    return new PaymentsServiceImpl(ledger, accounts, network, repository, intentEvents);
  }

  private PaymentIntent createdIntent(PaymentsService payments) {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    return payments.create(
        new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
  }

  /**
   * Race-loser repository: the winner committed a terminal transition first, so
   * the guarded UPDATE matches zero rows (false) and every later read observes
   * the winner's terminal state instead of CREATED.
   */
  private static final class LostTransitionRepository extends InMemoryPaymentsRepository {
    private final IntentStatus winnerStatus;
    private boolean raceLost;

    LostTransitionRepository(IntentStatus winnerStatus) {
      this.winnerStatus = winnerStatus;
    }

    @Override
    public boolean transitionToExpired(UUID publicId) {
      return loseRace();
    }

    @Override
    public boolean transitionToFailed(UUID publicId) {
      return loseRace();
    }

    @Override
    public Optional<PaymentIntent> findByPublicId(UUID publicId) {
      return super.findByPublicId(publicId).map(intent ->
          raceLost && intent.status() == IntentStatus.CREATED ? asWinner(intent) : intent);
    }

    private boolean loseRace() {
      raceLost = true;
      return false;
    }

    private PaymentIntent asWinner(PaymentIntent intent) {
      return new PaymentIntent(intent.publicId(), intent.accountPublicId(), intent.amount(),
          winnerStatus, intent.chargePublicId(), intent.expiresAt(), intent.createdAt(),
          intent.settledAt(), intent.journalTransactionPublicId());
    }
  }

  /** Records every publish so lost races can assert zero events. */
  private static final class RecordingIntentEvents implements IntentLifecycleEvents {
    final List<IntentLifecycleEvent> published = new ArrayList<>();

    @Override
    public void publish(IntentLifecycleEvent event) {
      published.add(event);
    }
  }
}
