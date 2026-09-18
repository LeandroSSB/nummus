package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import com.leandrossb.nummus.ledger.domain.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

/** In-memory fake for service unit tests; mirrors the database's unique reversal_of behavior. */
public class InMemoryLedgerRepository implements LedgerRepository {

  private final Map<UUID, LedgerAccount> accounts = new ConcurrentHashMap<>();
  private final Map<UUID, PostedTransaction> transactions = new ConcurrentHashMap<>();

  @Override
  public LedgerAccount insertAccount(LedgerAccount account) {
    accounts.put(account.publicId(), account);
    return account;
  }

  @Override
  public Optional<LedgerAccount> findAccount(UUID publicId) {
    return Optional.ofNullable(accounts.get(publicId));
  }

  @Override
  public boolean updateAccountStatus(UUID publicId, AccountStatus status, Instant closedAt) {
    var current = accounts.get(publicId);
    if (current == null) {
      return false;
    }
    accounts.put(publicId, new LedgerAccount(current.publicId(), current.name(), current.type(),
        current.currency(), status, current.openedAt(), closedAt));
    return true;
  }

  @Override
  public Optional<PostedTransaction> findTransaction(UUID publicId) {
    return Optional.ofNullable(transactions.get(publicId));
  }

  @Override
  public PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId,
      List<PostingDraft> postings) {
    if (reversalOfPublicId != null
        && transactions.values().stream().anyMatch(t -> reversalOfPublicId.equals(t.reversalOf()))) {
      throw new DataIntegrityViolationException(
          "duplicate reversal_of: " + reversalOfPublicId);
    }
    var posted = new PostedTransaction(UUID.randomUUID(), memo, Instant.now(), reversalOfPublicId,
        postings.stream()
            .map(d -> new PostedPosting(d.accountPublicId(), d.direction(), d.amount()))
            .toList());
    transactions.put(posted.publicId(), posted);
    return posted;
  }

  @Override
  public BigDecimal rawBalance(UUID accountPublicId) {
    return transactions.values().stream()
        .flatMap(t -> t.postings().stream())
        .filter(p -> p.accountPublicId().equals(accountPublicId))
        .map(p -> p.direction() == Direction.DEBIT
            ? p.amount().amount()
            : p.amount().amount().negate())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Override
  public List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit) {
    return transactions.values().stream()
        .sorted(Comparator.comparing(PostedTransaction::bookedAt).reversed())
        .flatMap(t -> t.postings().stream()
            .filter(p -> p.accountPublicId().equals(accountPublicId))
            .map(p -> new StatementLine(t.bookedAt(), t.publicId(), t.memo(), p.direction(),
                p.amount())))
        .skip(offset)
        .limit(limit)
        .toList();
  }
}
