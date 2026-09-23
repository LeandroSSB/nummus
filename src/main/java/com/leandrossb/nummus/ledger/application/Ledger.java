package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import java.util.UUID;

/**
 * The ledger module's internal API. M2+ modules call this port; HTTP arrives in M2.
 * Deliberately NOT idempotent — merchant-facing retry semantics belong to the
 * idempotency layer (M4), not to the accounting core.
 */
public interface Ledger {

  LedgerAccount openAccount(OpenAccountCommand cmd);

  LedgerAccount freezeAccount(UUID publicId);

  LedgerAccount closeAccount(UUID publicId);

  /** Restores a FROZEN account to ACTIVE. CLOSED is terminal. */
  LedgerAccount unfreezeAccount(UUID publicId);

  /** The ledger account by public id. */
  LedgerAccount getAccount(UUID publicId);

  /**
   * Row-locks the account's ledger row, holding the lock until the caller's
   * transaction commits — the fence that serializes check-then-reserve flows
   * (payout requests) against one account. Unknown accounts are rejected the
   * same way {@link #getAccount(UUID)} rejects them.
   */
  void lockAccount(UUID publicId);

  /** Appends a balanced journal transaction atomically. */
  PostedTransaction post(PostTransactionCommand cmd);

  /** Appends a compensating transaction mirroring the original's postings. */
  PostedTransaction reverse(UUID transactionPublicId, String memo);

  /** Raw derived balance (debits minus credits), signed regardless of the account's normal side. */
  Money balance(UUID accountPublicId);

  /** The account's postings, newest first, with its derived balance. */
  AccountStatement statement(UUID accountPublicId, Page page);

  PostedTransaction getTransaction(UUID txPublicId);
}
