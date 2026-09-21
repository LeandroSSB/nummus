package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.payments.domain.ChargeAmountMismatchException;
import com.leandrossb.nummus.payments.domain.ConcurrentSettlementException;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.domain.UnknownPaymentIntentException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentsServiceImpl implements PaymentsService {

  static final Duration DEFAULT_TTL = Duration.ofSeconds(1800);
  private static final Duration MIN_TTL = Duration.ofSeconds(60);
  private static final Duration MAX_TTL = Duration.ofSeconds(86400);

  private final Ledger ledger;
  private final AccountsService accounts;
  private final PaymentNetwork network;
  private final PaymentsRepository repository;
  private final IntentLifecycleEvents intentEvents;
  private final MerchantsService merchants;

  public PaymentsServiceImpl(Ledger ledger, AccountsService accounts, PaymentNetwork network,
      PaymentsRepository repository, IntentLifecycleEvents intentEvents,
      MerchantsService merchants) {
    this.ledger = ledger;
    this.accounts = accounts;
    this.network = network;
    this.repository = repository;
    this.intentEvents = intentEvents;
    this.merchants = merchants;
  }

  @Override
  @Transactional
  public PaymentIntent create(UUID merchantPublicId, CreateIntentCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    Duration ttl = cmd.ttl() == null ? DEFAULT_TTL : cmd.ttl();
    if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException(
          "ttl must be between 60 and 86400 seconds: " + ttl.toSeconds());
    }
    var account = accounts.get(merchantPublicId, cmd.accountPublicId());
    if (account.status() != AccountStatus.ACTIVE) {
      throw new PaymentAccountNotActiveException(account.publicId(), account.status());
    }
    var charge = network.createCharge(cmd.amount());
    return repository.insert(new PaymentIntent(UUID.randomUUID(), account.publicId(),
        cmd.amount(), IntentStatus.CREATED, charge.publicId(), Instant.now().plus(ttl),
        Instant.now(), null, null, null));
  }

  @Override
  @Transactional
  public PaymentIntent get(UUID merchantPublicId, UUID publicId) {
    var intent = repository.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownPaymentIntentException(publicId));
    // Ownership precedes every lazy transition and the charge poll: never act
    // on another merchant's intent — for them it is indistinguishable from
    // an unknown one, down to the vocabulary: the 404 names the intent they
    // addressed, never the owning account's id.
    try {
      accounts.get(merchantPublicId, intent.accountPublicId());
    } catch (UnknownPaymentAccountException e) {
      throw new UnknownPaymentIntentException(publicId);
    }
    if (intent.status() != IntentStatus.CREATED) {
      return intent;
    }
    if (Instant.now().isAfter(intent.expiresAt())) {
      // Publish only on a won transition; a racing winner already published
      // its event for the terminal state — the loser returns it silently.
      if (repository.transitionToExpired(publicId)) {
        var expired = repository.findByPublicId(publicId).orElseThrow();
        intentEvents.publish(toEvent(merchantPublicId, IntentEventTypes.EXPIRED, expired, null, null));
        return expired;
      }
      return repository.findByPublicId(publicId).orElseThrow();
    }
    var charge = network.getCharge(intent.chargePublicId());
    if (charge.amount().compareTo(intent.amount()) != 0) {
      throw new ChargeAmountMismatchException(charge.publicId(), intent.amount(), charge.amount());
    }
    return switch (charge.status()) {
      case PENDING -> intent;
      case FAILED -> {
        // Publish only on a won transition; a racing winner already published
        // its event for the terminal state — the loser returns it silently.
        if (repository.transitionToFailed(publicId)) {
          var failed = repository.findByPublicId(publicId).orElseThrow();
          intentEvents.publish(toEvent(merchantPublicId, IntentEventTypes.FAILED, failed, null, null));
          yield failed;
        }
        yield repository.findByPublicId(publicId).orElseThrow();
      }
      case SUCCEEDED -> settle(intent, merchantPublicId);
    };
  }

  @Override
  @Transactional
  public List<SettlementView> listSettlements(Instant from, Instant to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return repository.findSettledBetween(from, to).stream()
        .map(intent -> new SettlementView(intent.publicId(), intent.accountPublicId(),
            intent.chargePublicId(), intent.amount(), intent.settledAt(),
            intent.journalTransactionPublicId()))
        .toList();
  }

  private PaymentIntent settle(PaymentIntent intent, UUID merchantPublicId) {
    var account = accounts.get(merchantPublicId, intent.accountPublicId());
    if (account.status() != AccountStatus.ACTIVE) {
      throw new PaymentAccountNotActiveException(account.publicId(), account.status());
    }
    // The fee is a settle-time fact: whatever schedule the merchant carries when
    // the money moves is the one that prices this settlement.
    var schedule = merchants.findFeeSchedule(account.merchantPublicId()).orElse(FeeSchedule.ZERO);
    var breakdown = FeeCalculator.compute(intent.amount(), schedule);
    var postings = new ArrayList<PostingDraft>();
    postings.add(new PostingDraft(PaymentClearingAccount.PUBLIC_ID, Direction.DEBIT, intent.amount()));
    // A fully capped fee consumes the gross (net = 0); zero amounts are never
    // postable, so the merchant leg disappears rather than posts at 0.00.
    if (breakdown.net().isPositive()) {
      postings.add(new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, breakdown.net()));
    }
    if (breakdown.fee().isPositive()) {
      postings.add(new PostingDraft(FeeRevenueAccount.PUBLIC_ID, Direction.CREDIT, breakdown.fee()));
    }
    var posted = ledger.post(new PostTransactionCommand("settlement " + intent.publicId(), postings));
    if (!repository.markSettled(intent.publicId(), posted.publicId(), Instant.now(),
        breakdown.fee())) {
      // A racing settler won the guarded transition; roll this posting back with the
      // transaction and let the caller re-read the SETTLED state.
      throw new ConcurrentSettlementException(intent.publicId());
    }
    var settled = repository.findByPublicId(intent.publicId()).orElseThrow();
    intentEvents.publish(toEvent(merchantPublicId, IntentEventTypes.SETTLED, settled,
        breakdown.fee(), breakdown.net()));
    return settled;
  }

  private static IntentLifecycleEvent toEvent(UUID merchantPublicId, String type, PaymentIntent intent,
      Money fee, Money netAmount) {
    return new IntentLifecycleEvent(merchantPublicId, type, intent.publicId(), intent.accountPublicId(),
        intent.amount(), intent.status().name(), intent.chargePublicId(),
        intent.settledAt(), intent.journalTransactionPublicId(), fee, netAmount);
  }
}
