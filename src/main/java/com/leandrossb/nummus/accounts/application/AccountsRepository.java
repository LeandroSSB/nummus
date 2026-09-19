package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the accounts module. Reads and writes are scoped to the
 *  owning merchant; status transitions update only status and closed_at. */
public interface AccountsRepository {

  PaymentAccount insert(PaymentAccount account);

  Optional<PaymentAccount> findByPublicId(UUID merchantPublicId, UUID publicId);

  /** @return false when the account does not exist for this merchant. */
  boolean updateStatus(UUID merchantPublicId, UUID publicId, AccountStatus status, Instant closedAt);
}
