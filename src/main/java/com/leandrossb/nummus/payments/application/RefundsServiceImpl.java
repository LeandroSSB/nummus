package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.payments.domain.ConcurrentRefundException;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.IntentNotRefundableException;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.payments.domain.RefundAmountMismatchException;
import com.leandrossb.nummus.payments.domain.RefundExceedsRemainingException;
import com.leandrossb.nummus.payments.domain.RefundStatus;
import com.leandrossb.nummus.payments.domain.UnknownPaymentIntentException;
import com.leandrossb.nummus.payments.domain.UnknownRefundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundsServiceImpl implements RefundsService {

  static final Duration DEFAULT_TTL = Duration.ofSeconds(1800);
  private static final Duration MIN_TTL = Duration.ofSeconds(60);
  private static final Duration MAX_TTL = Duration.ofSeconds(86400);

  private final Ledger ledger;
  private final AccountsService accounts;
  private final PaymentsService payments;
  private final PaymentNetwork network;
  private final RefundsRepository repository;
  private final RefundLifecycleEvents refundEvents;

  public RefundsServiceImpl(Ledger ledger, AccountsService accounts, PaymentsService payments,
      PaymentNetwork network, RefundsRepository repository, RefundLifecycleEvents refundEvents) {
    this.ledger = ledger;
    this.accounts = accounts;
    this.payments = payments;
    this.network = network;
    this.repository = repository;
    this.refundEvents = refundEvents;
  }

  @Override
  @Transactional
  public Refund create(UUID merchantPublicId, UUID intentPublicId, CreateRefundCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    Duration ttl = cmd.ttl() == null ? DEFAULT_TTL : cmd.ttl();
    if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException(
          "ttl must be between 60 and 86400 seconds: " + ttl.toSeconds());
    }
    var intent = payments.get(merchantPublicId, intentPublicId);
    if (intent.status() != IntentStatus.SETTLED) {
      throw new IntentNotRefundableException(intentPublicId, intent.status());
    }
    var account = accounts.get(merchantPublicId, intent.accountPublicId());
    if (account.status() != AccountStatus.ACTIVE) {
      throw new PaymentAccountNotActiveException(account.publicId(), account.status());
    }
    // One serialization point: the intent's ledger-account row lock, shared
    // with payout requests. Under it, the refundable sum and the available
    // balance are both race-free — a competing refund or payout on this
    // account cannot interleave between the checks and the hold posting.
    ledger.lockAccount(account.ledgerAccountPublicId());
    var refunded = refundedTotal(intentPublicId);
    var remaining = intent.amount().subtract(refunded);
    if (cmd.amount().compareTo(remaining) > 0) {
      throw new RefundExceedsRemainingException(intentPublicId, remaining, cmd.amount());
    }
    var available = accounts.balance(merchantPublicId, account.publicId());
    if (available.compareTo(cmd.amount()) < 0) {
      throw new InsufficientFundsException(account.publicId(), available, cmd.amount());
    }
    var refundId = UUID.randomUUID();
    var networkRefund = network.createChargeRefund(intent.chargePublicId(), cmd.amount());
    var hold = ledger.post(new PostTransactionCommand("refund " + refundId + " request",
        List.of(new PostingDraft(account.ledgerAccountPublicId(), Direction.DEBIT, cmd.amount()),
            new PostingDraft(RefundReservedAccount.PUBLIC_ID, Direction.CREDIT, cmd.amount()))));
    return repository.insert(new Refund(refundId, intent.publicId(), cmd.amount(),
        RefundStatus.REQUESTED, networkRefund.publicId(), Instant.now().plus(ttl),
        Instant.now(), null, hold.publicId(), null, null));
  }

  @Override
  @Transactional
  public Refund get(UUID merchantPublicId, UUID publicId) {
    var refund = repository.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownRefundException(publicId));
    // Ownership precedes every lazy transition and the refund poll: never act
    // on another merchant's refund — for them it is indistinguishable from an
    // unknown one. The refund reaches its merchant one hop deeper than the
    // payout: through the intent's account (the PaymentsServiceImpl.get
    // precedent).
    try {
      accounts.get(merchantPublicId,
          payments.get(merchantPublicId, refund.intentPublicId()).accountPublicId());
    } catch (UnknownPaymentIntentException | UnknownPaymentAccountException e) {
      throw new UnknownRefundException(publicId);
    }
    if (refund.status() != RefundStatus.REQUESTED) {
      return refund;
    }
    // The payout-expiry shape exactly: refund expiry MOVES MONEY, so post the
    // return legs first, then the guarded mark. A lost race throws so the
    // transaction — posting included — rolls back.
    if (Instant.now().isAfter(refund.expiresAt())) {
      var posted = returnLegs(merchantPublicId, refund);
      if (!repository.markExpired(publicId, posted.publicId())) {
        throw new ConcurrentRefundException(publicId);
      }
      var expired = repository.findByPublicId(publicId).orElseThrow();
      refundEvents.publish(toEvent(merchantPublicId, RefundEventTypes.EXPIRED, expired));
      return expired;
    }
    var networkRefund = network.getChargeRefund(refund.networkRefundPublicId());
    if (networkRefund.amount().compareTo(refund.amount()) != 0) {
      throw new RefundAmountMismatchException(networkRefund.publicId(), refund.amount(),
          networkRefund.amount());
    }
    return switch (networkRefund.status()) {
      case PENDING -> refund;
      case FAILED -> {
        var posted = returnLegs(merchantPublicId, refund);
        if (!repository.markFailed(publicId, posted.publicId())) {
          throw new ConcurrentRefundException(publicId);
        }
        var failed = repository.findByPublicId(publicId).orElseThrow();
        refundEvents.publish(toEvent(merchantPublicId, RefundEventTypes.FAILED, failed));
        yield failed;
      }
      case SUCCEEDED -> execute(refund, merchantPublicId);
    };
  }

  /** Releases the hold: the reserve debits what the request credited, the
   *  intent's ledger account is made whole. */
  private PostedTransaction returnLegs(UUID merchantPublicId, Refund refund) {
    var ledgerAccount = accounts.get(merchantPublicId,
        payments.get(merchantPublicId, refund.intentPublicId()).accountPublicId())
        .ledgerAccountPublicId();
    return ledger.post(new PostTransactionCommand("refund " + refund.publicId() + " return",
        List.of(new PostingDraft(RefundReservedAccount.PUBLIC_ID, Direction.DEBIT, refund.amount()),
            new PostingDraft(ledgerAccount, Direction.CREDIT, refund.amount()))));
  }

  /** Settles the refund out of the reserve and back to the network. No fee
   *  legs ever: processing fees are retained, never reversed. */
  private Refund execute(Refund refund, UUID merchantPublicId) {
    var postings = new ArrayList<PostingDraft>();
    postings.add(new PostingDraft(RefundReservedAccount.PUBLIC_ID, Direction.DEBIT, refund.amount()));
    postings.add(new PostingDraft(PaymentClearingAccount.PUBLIC_ID, Direction.CREDIT, refund.amount()));
    var posted = ledger.post(new PostTransactionCommand("refund " + refund.publicId() + " execute", postings));
    if (!repository.markSettled(refund.publicId(), posted.publicId(), Instant.now())) {
      // A racing execution won the guarded transition; roll this posting back
      // with the transaction and let the caller re-read the SETTLED state.
      throw new ConcurrentRefundException(refund.publicId());
    }
    var settled = repository.findByPublicId(refund.publicId()).orElseThrow();
    refundEvents.publish(toEvent(merchantPublicId, RefundEventTypes.SETTLED, settled));
    return settled;
  }

  /** The event carries the intent's account — the refund row is intent-scoped,
   *  so the account resolves through the intent (the same hop ownership
   *  takes). The journal link is the transition's own: execution on SETTLED,
   *  return on FAILED and EXPIRED. */
  private RefundLifecycleEvent toEvent(UUID merchantPublicId, String type, Refund refund) {
    return new RefundLifecycleEvent(merchantPublicId, type, refund.publicId(),
        refund.intentPublicId(),
        payments.get(merchantPublicId, refund.intentPublicId()).accountPublicId(),
        refund.amount(), refund.status().name(), refund.networkRefundPublicId(),
        refund.settledAt(),
        refund.status() == RefundStatus.SETTLED
            ? refund.executeTransactionPublicId() : refund.returnTransactionPublicId());
  }

  /** Sum of the holding (REQUESTED) and executed (SETTLED) refunds of the
   *  intent — the payments-side mirror of the network's own never-over-refund
   *  cap; FAILED and EXPIRED refunds release the remainder they held. */
  private Money refundedTotal(UUID intentPublicId) {
    return repository.refundedTotal(intentPublicId);
  }
}
