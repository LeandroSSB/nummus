package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import java.util.UUID;

/**
 * The accounts module's internal API. Every payment account wraps exactly one
 * LIABILITY ledger account; balances and statements are derived by the ledger
 * and presented with the holder's natural sign.
 */
public interface AccountsService {

  PaymentAccount open(OpenAccountCommand cmd);

  PaymentAccount get(UUID publicId);

  PaymentAccount freeze(UUID publicId);

  PaymentAccount unfreeze(UUID publicId);

  PaymentAccount close(UUID publicId);

  /** Derived balance in natural sign: available funds read positive. */
  Money balance(UUID publicId);

  /** Postings newest first with the natural-signed balance; lines stay as posted. */
  AccountStatement statement(UUID publicId, Page page);
}
