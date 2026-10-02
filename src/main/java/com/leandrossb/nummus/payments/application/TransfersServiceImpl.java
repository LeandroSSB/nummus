package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.payments.domain.CreateTransferCommand;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.Transfer;
import com.leandrossb.nummus.payments.domain.UnknownTransferException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransfersServiceImpl implements TransfersService {

  private final AccountsService accounts;
  private final Ledger ledger;
  private final TransfersRepository repository;
  private final TransferLifecycleEvents events;
  private final MeterRegistry registry;

  public TransfersServiceImpl(AccountsService accounts, Ledger ledger,
      TransfersRepository repository, TransferLifecycleEvents events, MeterRegistry registry) {
    this.accounts = accounts;
    this.ledger = ledger;
    this.repository = repository;
    this.events = events;
    this.registry = registry;
  }

  @Override
  @Transactional
  public Transfer create(UUID merchantPublicId, CreateTransferCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    if (cmd.fromAccountPublicId().equals(cmd.toAccountPublicId())) {
      throw new IllegalArgumentException(
          "from and to accounts must differ: " + cmd.fromAccountPublicId());
    }
    var from = requireActive(merchantPublicId, cmd.fromAccountPublicId());
    var to = requireActive(merchantPublicId, cmd.toAccountPublicId());
    // Check-then-post under the from-account's row lock — the payout
    // reservation precedent minus the network legs. The lock serializes
    // concurrent transfers and payouts against the same account.
    ledger.lockAccount(from.ledgerAccountPublicId());
    var available = accounts.balance(merchantPublicId, from.publicId());
    if (available.compareTo(cmd.amount()) < 0) {
      throw new InsufficientFundsException(from.publicId(), available, cmd.amount());
    }
    var publicId = UUID.randomUUID();
    var posted = ledger.post(new PostTransactionCommand("transfer " + publicId,
        List.of(new PostingDraft(from.ledgerAccountPublicId(), Direction.DEBIT, cmd.amount()),
            new PostingDraft(to.ledgerAccountPublicId(), Direction.CREDIT, cmd.amount()))));
    var transfer = new Transfer(publicId, merchantPublicId, from.publicId(), to.publicId(),
        cmd.amount(), posted.publicId(), Instant.now());
    var stored = repository.insert(transfer);
    events.publish(toEvent(merchantPublicId, TransferEventTypes.COMPLETED, stored));
    count("nummus.transfers", "completed");
    return stored;
  }

  @Override
  @Transactional(readOnly = true)
  public Transfer get(UUID merchantPublicId, UUID publicId) {
    var transfer = repository.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownTransferException(publicId));
    // Ownership by the denormalized merchant link — for another merchant a
    // transfer is indistinguishable from an unknown one (the payout get
    // precedent, minus the accounts seam this column spares us).
    if (!transfer.merchantPublicId().equals(merchantPublicId)) {
      throw new UnknownTransferException(publicId);
    }
    return transfer;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Transfer> list(UUID merchantPublicId, UUID after, int limit) {
    return repository.listByMerchant(merchantPublicId, after, limit);
  }

  /** Resolves the merchant's account and requires ACTIVE — the freeze guard
   *  applies to internal moves too. Ownership stays masked as unknown. */
  private PaymentAccount requireActive(UUID merchantPublicId, UUID accountPublicId) {
    var account = accounts.get(merchantPublicId, accountPublicId);
    if (account.status() != AccountStatus.ACTIVE) {
      throw new PaymentAccountNotActiveException(account.publicId(), account.status());
    }
    return account;
  }

  /** One line per completed transfer: the counter name carries the fact,
   *  Prometheus renders it as nummus_transfers_total{outcome=...}. */
  private void count(String name, String outcome) {
    registry.counter(name, "outcome", outcome).increment();
  }

  private static TransferLifecycleEvent toEvent(UUID merchantPublicId, String type,
      Transfer transfer) {
    return new TransferLifecycleEvent(merchantPublicId, type, transfer.publicId(),
        transfer.fromAccountPublicId(), transfer.toAccountPublicId(), transfer.amount(),
        transfer.journalTransactionPublicId(), transfer.createdAt());
  }
}
