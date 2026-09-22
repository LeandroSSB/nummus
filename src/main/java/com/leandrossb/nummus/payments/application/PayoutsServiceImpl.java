package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import java.time.Duration;
import java.time.Instant;
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

  public PayoutsServiceImpl(Ledger ledger, AccountsService accounts, PaymentNetwork network,
      PayoutsRepository repository) {
    this.ledger = ledger;
    this.accounts = accounts;
    this.network = network;
    this.repository = repository;
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
    // reservation posting itself is what later requests derive from.
    ledger.lockAccount(account.ledgerAccountPublicId());
    var available = accounts.balance(merchantPublicId, account.publicId());
    if (available.compareTo(cmd.amount()) < 0) {
      throw new InsufficientFundsException(account.publicId(), available, cmd.amount());
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
}
