package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the ledger module. Implementations append transaction and
 * postings atomically; database triggers re-assert every invariant at commit.
 * {@code insertTransaction} signals a duplicate reversal with
 * {@link org.springframework.dao.DataIntegrityViolationException}.
 */
public interface LedgerRepository {

  LedgerAccount insertAccount(LedgerAccount account);

  Optional<LedgerAccount> findAccount(UUID publicId);

  /** @return false when the account does not exist. */
  boolean updateAccountStatus(UUID publicId, AccountStatus status, Instant closedAt);

  Optional<PostedTransaction> findTransaction(UUID publicId);

  /** Appends the transaction and its postings in one database transaction. */
  PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId, List<PostingDraft> postings);

  /** Sum of DEBIT minus CREDIT postings for the account; 0 for an account without postings. */
  BigDecimal rawBalance(UUID accountPublicId);

  /** The account's postings newest first. */
  List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit);
}
