package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.payments.domain.ChargeAmountMismatchException;
import com.leandrossb.nummus.payments.domain.ConcurrentSettlementException;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.domain.UnknownPaymentIntentException;
import java.time.Duration;
import java.time.Instant;
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

  public PaymentsServiceImpl(Ledger ledger, AccountsService accounts, PaymentNetwork network,
      PaymentsRepository repository, IntentLifecycleEvents intentEvents) {
    this.ledger = ledger;
    this.accounts = accounts;
    this.network = network;
    this.repository = repository;
    this.intentEvents = intentEvents;
  }

  @Override
  @Transactional
  public PaymentIntent create(CreateIntentCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    Duration ttl = cmd.ttl() == null ? DEFAULT_TTL : cmd.ttl();
    if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException(
          "ttl must be between 60 and 86400 seconds: " + ttl.toSeconds());
    }
    var account = accounts.get(cmd.accountPublicId());
    if (account.status() != AccountStatus.ACTIVE) {
      throw new PaymentAccountNotActiveException(account.publicId(), account.status());
    }
    var charge = network.createCharge(cmd.amount());
    return repository.insert(new PaymentIntent(UUID.randomUUID(), account.publicId(),
        cmd.amount(), IntentStatus.CREATED, charge.publicId(), Instant.now().plus(ttl),
        Instant.now(), null, null));
  }

  @Override
  @Transactional
  public PaymentIntent get(UUID publicId) {
    var intent = repository.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownPaymentIntentException(publicId));
    if (intent.status() != IntentStatus.CREATED) {
      return intent;
    }
    if (Instant.now().isAfter(intent.expiresAt())) {
      repository.transitionToExpired(publicId);
      var expired = repository.findByPublicId(publicId).orElseThrow();
      intentEvents.publish(toEvent(IntentEventTypes.EXPIRED, expired));
      return expired;
    }
    var charge = network.getCharge(intent.chargePublicId());
    if (charge.amount().compareTo(intent.amount()) != 0) {
      throw new ChargeAmountMismatchException(charge.publicId(), intent.amount(), charge.amount());
    }
    return switch (charge.status()) {
      case PENDING -> intent;
      case FAILED -> {
        repository.transitionToFailed(publicId);
        var failed = repository.findByPublicId(publicId).orElseThrow();
        intentEvents.publish(toEvent(IntentEventTypes.FAILED, failed));
        yield failed;
      }
      case SUCCEEDED -> settle(intent);
    };
  }

  private PaymentIntent settle(PaymentIntent intent) {
    var account = accounts.get(intent.accountPublicId());
    if (account.status() != AccountStatus.ACTIVE) {
      throw new PaymentAccountNotActiveException(account.publicId(), account.status());
    }
    var posted = ledger.post(new PostTransactionCommand(
        "settlement " + intent.publicId(), List.of(
            new PostingDraft(PaymentClearingAccount.PUBLIC_ID, Direction.DEBIT, intent.amount()),
            new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, intent.amount()))));
    if (!repository.markSettled(intent.publicId(), posted.publicId(), Instant.now())) {
      // A racing settler won the guarded transition; roll this posting back with the
      // transaction and let the caller re-read the SETTLED state.
      throw new ConcurrentSettlementException(intent.publicId());
    }
    var settled = repository.findByPublicId(intent.publicId()).orElseThrow();
    intentEvents.publish(toEvent(IntentEventTypes.SETTLED, settled));
    return settled;
  }

  private static IntentLifecycleEvent toEvent(String type, PaymentIntent intent) {
    return new IntentLifecycleEvent(type, intent.publicId(), intent.accountPublicId(),
        intent.amount(), intent.status().name(), intent.chargePublicId(),
        intent.settledAt(), intent.journalTransactionPublicId());
  }
}
