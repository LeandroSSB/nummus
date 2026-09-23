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
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.payments.domain.ConcurrentPayoutException;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import com.leandrossb.nummus.payments.domain.TransferAmountMismatchException;
import com.leandrossb.nummus.payments.domain.UnknownPayoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayoutsServiceImpl implements PayoutsService {

  static final Duration DEFAULT_TTL = Duration.ofSeconds(1800);
  private static final Duration MIN_TTL = Duration.ofSeconds(60);
  private static final Duration MAX_TTL = Duration.ofSeconds(86400);

  private final Ledger ledger;
  private final AccountsService accounts;
  private final PaymentNetwork network;
  private final PayoutsRepository repository;
  private final PayoutLifecycleEvents payoutEvents;
  private final MerchantsService merchants;

  public PayoutsServiceImpl(Ledger ledger, AccountsService accounts, PaymentNetwork network,
      PayoutsRepository repository, PayoutLifecycleEvents payoutEvents, MerchantsService merchants) {
    this.ledger = ledger;
    this.accounts = accounts;
    this.network = network;
    this.repository = repository;
    this.payoutEvents = payoutEvents;
    this.merchants = merchants;
  }

  @Override
  @Transactional
  public Payout create(UUID merchantPublicId, CreatePayoutCommand cmd) {
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
    // Check-then-reserve cannot race: the row lock on the merchant's ledger
    // account serializes every payout request against this account, and the
    // reservation posting itself is what later requests derive from. The
    // check prices in the execution fee: the fee leg debits the merchant's
    // account at settle time, so a full-balance request under a positive fee
    // would land the merchant at exactly -fee.
    ledger.lockAccount(account.ledgerAccountPublicId());
    var schedule = merchants.findFeeSchedule(merchantPublicId).orElse(FeeSchedule.ZERO);
    var payoutFee = Money.of(schedule.payoutFixedAmount(), cmd.amount().currency());
    var available = accounts.balance(merchantPublicId, account.publicId());
    var includingFee = cmd.amount().add(payoutFee);
    if (available.compareTo(includingFee) < 0) {
      throw new InsufficientFundsException(account.publicId(), available, includingFee);
    }
    var payoutId = UUID.randomUUID();
    var transfer = network.createPayoutTransfer(cmd.amount(), cmd.destinationBankKey());
    var reservation = ledger.post(new PostTransactionCommand("payout " + payoutId + " request",
        List.of(new PostingDraft(account.ledgerAccountPublicId(), Direction.DEBIT, cmd.amount()),
            new PostingDraft(PayoutReservedAccount.PUBLIC_ID, Direction.CREDIT, cmd.amount()))));
    return repository.insert(new Payout(payoutId, account.publicId(), cmd.amount(),
        PayoutStatus.REQUESTED, cmd.destinationBankKey(), transfer.publicId(),
        Instant.now().plus(ttl), Instant.now(), null, null, reservation.publicId(), null, null));
  }

  @Override
  @Transactional
  public Payout get(UUID merchantPublicId, UUID publicId) {
    var payout = repository.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownPayoutException(publicId));
    // Ownership precedes every lazy transition and the transfer poll: never act
    // on another merchant's payout — for them it is indistinguishable from an
    // unknown one (the PaymentsServiceImpl.get precedent).
    try {
      accounts.get(merchantPublicId, payout.accountPublicId());
    } catch (UnknownPaymentAccountException e) {
      throw new UnknownPayoutException(publicId);
    }
    if (payout.status() != PayoutStatus.REQUESTED) {
      return payout;
    }
    var transfer = network.getPayoutTransfer(payout.transferPublicId());
    if (transfer.amount().compareTo(payout.amount()) != 0) {
      throw new TransferAmountMismatchException(transfer.publicId(), payout.amount(), transfer.amount());
    }
    // Poll-then-decide: an executed instruction settles even past expiry —
    // the money moved; a pending one is cancelled, then the hold returns.
    // A cancel that loses to a parallel pay re-reads SUCCEEDED and settles.
    if (Instant.now().isAfter(payout.expiresAt())) {
      return switch (transfer.status()) {
        case SUCCEEDED -> execute(payout, merchantPublicId);
        case PENDING -> {
          var after = network.cancelPayoutTransfer(payout.transferPublicId());
          yield switch (after.status()) {
            case SUCCEEDED -> execute(payout, merchantPublicId);
            case FAILED -> toFailed(payout, merchantPublicId);
            default -> toExpired(payout, merchantPublicId);
          };
        }
        // The network's verdict outranks the timeout: a failed instruction
        // fails the payout wherever the expiry clock stands.
        case FAILED -> toFailed(payout, merchantPublicId);
        default -> toExpired(payout, merchantPublicId);
      };
    }
    return switch (transfer.status()) {
      case PENDING -> payout;
      case CANCELLED, FAILED -> toFailed(payout, merchantPublicId);
      case SUCCEEDED -> execute(payout, merchantPublicId);
    };
  }

  @Override
  @Transactional
  public List<MoneyOutSettlementView> listSettlements(Instant from, Instant to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return repository.findSettledBetween(from, to).stream()
        .map(payout -> new MoneyOutSettlementView(payout.publicId(), payout.transferPublicId(),
            payout.amount(), payout.settledAt()))
        .toList();
  }

  /** Expires a REQUESTED payout: the hold timed out and comes home. Unlike
   *  intent expiry this MOVES MONEY: the return legs post first, then the
   *  guarded mark. A lost race throws so the transaction — posting included —
   *  rolls back (the ConcurrentSettlement precedent). */
  private Payout toExpired(Payout payout, UUID merchantPublicId) {
    var posted = returnLegs(merchantPublicId, payout);
    if (!repository.markExpired(payout.publicId(), posted.publicId())) {
      throw new ConcurrentPayoutException(payout.publicId());
    }
    var expired = repository.findByPublicId(payout.publicId()).orElseThrow();
    payoutEvents.publish(toEvent(merchantPublicId, PayoutEventTypes.EXPIRED, expired, null, null));
    return expired;
  }

  /** Fails a REQUESTED payout — the network's verdict on the instruction,
   *  wherever the expiry clock stands (a pre-expiry read observing a CANCELLED
   *  rides along: the hold must still come home). Same race shape as
   *  {@link #toExpired}: post, guard, throw on loss. */
  private Payout toFailed(Payout payout, UUID merchantPublicId) {
    var posted = returnLegs(merchantPublicId, payout);
    if (!repository.markFailed(payout.publicId(), posted.publicId())) {
      throw new ConcurrentPayoutException(payout.publicId());
    }
    var failed = repository.findByPublicId(payout.publicId()).orElseThrow();
    payoutEvents.publish(toEvent(merchantPublicId, PayoutEventTypes.FAILED, failed, null, null));
    return failed;
  }

  /** Releases the reservation: the reserve debits what the request credited,
   *  the merchant's ledger account is made whole. */
  private PostedTransaction returnLegs(UUID merchantPublicId, Payout payout) {
    var ledgerAccount = accounts.get(merchantPublicId, payout.accountPublicId()).ledgerAccountPublicId();
    return ledger.post(new PostTransactionCommand("payout " + payout.publicId() + " return",
        List.of(new PostingDraft(PayoutReservedAccount.PUBLIC_ID, Direction.DEBIT, payout.amount()),
            new PostingDraft(ledgerAccount, Direction.CREDIT, payout.amount()))));
  }

  /** Settles the payout out of the reserve. The fee is a settle-time fact:
   *  whatever schedule the merchant carries when the money moves is the one
   *  that prices this execution (the settle precedent). */
  private Payout execute(Payout payout, UUID merchantPublicId) {
    var ledgerAccount = accounts.get(merchantPublicId, payout.accountPublicId()).ledgerAccountPublicId();
    var schedule = merchants.findFeeSchedule(merchantPublicId).orElse(FeeSchedule.ZERO);
    var fee = Money.of(schedule.payoutFixedAmount(), payout.amount().currency());
    var postings = new ArrayList<PostingDraft>();
    postings.add(new PostingDraft(PayoutReservedAccount.PUBLIC_ID, Direction.DEBIT, payout.amount()));
    postings.add(new PostingDraft(PaymentClearingAccount.PUBLIC_ID, Direction.CREDIT, payout.amount()));
    if (fee.isPositive()) {
      postings.add(new PostingDraft(ledgerAccount, Direction.DEBIT, fee));
      postings.add(new PostingDraft(FeeRevenueAccount.PUBLIC_ID, Direction.CREDIT, fee));
    }
    var posted = ledger.post(new PostTransactionCommand("payout " + payout.publicId() + " execute", postings));
    if (!repository.markSettled(payout.publicId(), posted.publicId(), Instant.now(), fee)) {
      // A racing execution won the guarded transition; roll this posting back
      // with the transaction and let the caller re-read the SETTLED state.
      throw new ConcurrentPayoutException(payout.publicId());
    }
    var settled = repository.findByPublicId(payout.publicId()).orElseThrow();
    payoutEvents.publish(toEvent(merchantPublicId, PayoutEventTypes.SETTLED, settled, fee,
        payout.amount().subtract(fee)));
    return settled;
  }

  private static PayoutLifecycleEvent toEvent(UUID merchantPublicId, String type, Payout payout,
      Money fee, Money netAmount) {
    return new PayoutLifecycleEvent(merchantPublicId, type, payout.publicId(),
        payout.accountPublicId(), payout.amount(), payout.status().name(),
        payout.transferPublicId(), payout.destinationBankKey(), payout.settledAt(),
        payout.status() == PayoutStatus.SETTLED
            ? payout.executeTransactionPublicId() : payout.returnTransactionPublicId(),
        fee, netAmount);
  }
}
