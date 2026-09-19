package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fake for accounts service unit tests. */
public class InMemoryAccountsRepository implements AccountsRepository {

  private final Map<UUID, PaymentAccount> accounts = new ConcurrentHashMap<>();

  @Override
  public PaymentAccount insert(PaymentAccount account) {
    accounts.put(account.publicId(), account);
    return account;
  }

  @Override
  public Optional<PaymentAccount> findByPublicId(UUID merchantPublicId, UUID publicId) {
    return Optional.ofNullable(accounts.get(publicId))
        .filter(account -> account.merchantPublicId().equals(merchantPublicId));
  }

  @Override
  public boolean updateStatus(UUID merchantPublicId, UUID publicId, AccountStatus status, Instant closedAt) {
    var current = accounts.get(publicId);
    if (current == null || !current.merchantPublicId().equals(merchantPublicId)) {
      return false;
    }
    accounts.put(publicId, new PaymentAccount(current.merchantPublicId(), current.publicId(),
        current.holderName(), status, current.openedAt(), closedAt, current.ledgerAccountPublicId()));
    return true;
  }
}
