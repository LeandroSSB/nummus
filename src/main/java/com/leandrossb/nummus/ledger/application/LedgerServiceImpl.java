package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.TooFewPostingsException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnbalancedTransactionException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerServiceImpl implements Ledger {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final LedgerRepository repository;

  public LedgerServiceImpl(LedgerRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public LedgerAccount openAccount(OpenAccountCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    if (cmd.name() == null || cmd.name().isBlank()) {
      throw new IllegalArgumentException("account name must not be blank");
    }
    Objects.requireNonNull(cmd.type(), "account type must not be null");
    if (!BRL.equals(cmd.currency())) {
      throw new CurrencyMismatchException(
          "the ledger is BRL-only, got: " + (cmd.currency() == null ? "null" : cmd.currency().getCurrencyCode()));
    }
    return repository.insertAccount(new LedgerAccount(UUID.randomUUID(), cmd.name().trim(),
        cmd.type(), BRL, AccountStatus.ACTIVE, Instant.now(), null));
  }

  @Override
  @Transactional
  public LedgerAccount freezeAccount(UUID publicId) {
    return transitionStatus(publicId, AccountStatus.FROZEN, null);
  }

  @Override
  @Transactional
  public LedgerAccount closeAccount(UUID publicId) {
    return transitionStatus(publicId, AccountStatus.CLOSED, Instant.now());
  }

  @Override
  @Transactional
  public LedgerAccount unfreezeAccount(UUID publicId) {
    return transitionStatus(publicId, AccountStatus.ACTIVE, null);
  }

  @Override
  @Transactional(readOnly = true)
  public LedgerAccount getAccount(UUID publicId) {
    return requireAccount(publicId);
  }

  @Override
  @Transactional
  public void lockAccount(UUID publicId) {
    repository.lockAccount(publicId);
  }

  private LedgerAccount transitionStatus(UUID publicId, AccountStatus target, Instant closedAt) {
    var current = repository.findAccount(publicId)
        .orElseThrow(() -> new UnknownAccountException(publicId));
    if (current.status() == AccountStatus.CLOSED) {
      throw new AccountNotActiveException(publicId, current.status());
    }
    repository.updateAccountStatus(publicId, target, closedAt);
    return repository.findAccount(publicId).orElseThrow();
  }

  @Override
  @Transactional
  public PostedTransaction post(PostTransactionCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    requireValidPostings(cmd.postings());
    return repository.insertTransaction(cmd.memo(), null, cmd.postings());
  }

  @Override
  @Transactional
  public PostedTransaction reverse(UUID transactionPublicId, String memo) {
    var original = repository.findTransaction(transactionPublicId)
        .orElseThrow(() -> new UnknownTransactionException(transactionPublicId));
    List<PostingDraft> mirrored = original.postings().stream()
        .map(p -> new PostingDraft(p.accountPublicId(), p.direction().opposite(), p.amount()))
        .toList();
    // Mirrored postings are count/side/balance-valid by construction; validating
    // them here reuses the account-existence/ACTIVE/currency checks so a frozen
    // or closed account fails fast with a domain exception instead of surfacing
    // the deferred trigger's commit-time error as raw infrastructure failure.
    requireValidPostings(mirrored);
    try {
      return repository.insertTransaction(memo, transactionPublicId, mirrored);
    } catch (DataIntegrityViolationException e) {
      throw new TransactionAlreadyReversedException(transactionPublicId, e);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Money balance(UUID accountPublicId) {
    var account = requireAccount(accountPublicId);
    return Money.of(repository.rawBalance(accountPublicId), account.currency());
  }

  @Override
  @Transactional(readOnly = true)
  public AccountStatement statement(UUID accountPublicId, Page page) {
    Objects.requireNonNull(page, "page must not be null");
    var account = requireAccount(accountPublicId);
    return new AccountStatement(account,
        Money.of(repository.rawBalance(accountPublicId), account.currency()),
        repository.statementLines(accountPublicId, page.offset(), page.limit()));
  }

  @Override
  @Transactional(readOnly = true)
  public PostedTransaction getTransaction(UUID txPublicId) {
    return repository.findTransaction(txPublicId)
        .orElseThrow(() -> new UnknownTransactionException(txPublicId));
  }

  private LedgerAccount requireAccount(UUID publicId) {
    return repository.findAccount(publicId).orElseThrow(() -> new UnknownAccountException(publicId));
  }

  private void requireValidPostings(List<PostingDraft> postings) {
    if (postings == null || postings.size() < 2) {
      throw new TooFewPostingsException(postings == null ? 0 : postings.size());
    }
    boolean hasDebit = postings.stream().anyMatch(p -> p.direction() == Direction.DEBIT);
    boolean hasCredit = postings.stream().anyMatch(p -> p.direction() == Direction.CREDIT);
    if (!hasDebit || !hasCredit) {
      throw new TooFewPostingsException(postings.size());
    }
    for (PostingDraft draft : postings) {
      var account = repository.findAccount(draft.accountPublicId())
          .orElseThrow(() -> new UnknownAccountException(draft.accountPublicId()));
      if (account.status() != AccountStatus.ACTIVE) {
        throw new AccountNotActiveException(draft.accountPublicId(), account.status());
      }
      if (!BRL.equals(draft.amount().currency())) {
        throw new CurrencyMismatchException(
            "the ledger is BRL-only, got: " + draft.amount().currency().getCurrencyCode());
      }
    }
    Money debits = total(postings, Direction.DEBIT);
    Money credits = total(postings, Direction.CREDIT);
    if (debits.compareTo(credits) != 0) {
      throw new UnbalancedTransactionException(debits, credits);
    }
  }

  private Money total(List<PostingDraft> postings, Direction direction) {
    return postings.stream()
        .filter(p -> p.direction() == direction)
        .map(PostingDraft::amount)
        .reduce(Money.ofBrl("0"), Money::add);
  }
}
