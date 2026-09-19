package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import java.util.UUID;

/**
 * The accounts module's internal API. Every payment account is owned by a
 * merchant and wraps exactly one LIABILITY ledger account; balances and
 * statements are derived by the ledger and presented with the holder's
 * natural sign. Every lookup is scoped to the owning merchant — another
 * merchant's account is indistinguishable from an unknown one.
 */
public interface AccountsService {

  PaymentAccount open(UUID merchantPublicId, OpenAccountCommand cmd);

  PaymentAccount get(UUID merchantPublicId, UUID publicId);

  PaymentAccount freeze(UUID merchantPublicId, UUID publicId);

  PaymentAccount unfreeze(UUID merchantPublicId, UUID publicId);

  PaymentAccount close(UUID merchantPublicId, UUID publicId);

  /** Derived balance in natural sign: available funds read positive. */
  Money balance(UUID merchantPublicId, UUID publicId);

  /** Postings newest first with the natural-signed balance; lines stay as posted. */
  AccountStatement statement(UUID merchantPublicId, UUID publicId, Page page);
}
