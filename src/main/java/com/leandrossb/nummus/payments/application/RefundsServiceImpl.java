package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.IntentNotRefundableException;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.payments.domain.RefundExceedsRemainingException;
import com.leandrossb.nummus.payments.domain.RefundStatus;
import java.time.Duration;
import java.time.Instant;
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

  public RefundsServiceImpl(Ledger ledger, AccountsService accounts, PaymentsService payments,
      PaymentNetwork network, RefundsRepository repository) {
    this.ledger = ledger;
    this.accounts = accounts;
    this.payments = payments;
    this.network = network;
    this.repository = repository;
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

  /** Sum of the holding (REQUESTED) and executed (SETTLED) refunds of the
   *  intent — the payments-side mirror of the network's own never-over-refund
   *  cap; FAILED and EXPIRED refunds release the remainder they held. */
  private Money refundedTotal(UUID intentPublicId) {
    return repository.refundedTotal(intentPublicId);
  }
}
