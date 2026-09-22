package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.Optional;
import java.util.UUID;

/** Merchant onboarding and lookup. */
public interface MerchantsService {

  /** Creates the merchant and records {@code merchant.created} in the audit
   *  log. @param actingOperatorKey the calling operator key's public id — the
   *  audit entry's actor. Null records no entry (an action without an
   *  attributable caller); the HTTP surface always resolves and passes the
   *  authenticated key. */
  Merchant create(String name, FeeSchedule fee, UUID actingOperatorKey);

  /** Resolves a raw {@code nummus_sk_…} key to its merchant and key; empty for
   *  unknown, revoked, or expired keys. */
  Optional<ResolvedMerchantKey> findByApiKey(String rawKey);

  Optional<Merchant> find(UUID publicId);

  Optional<FeeSchedule> findFeeSchedule(UUID publicId);

  /** Appends an attributed history entry and updates the cached current
   *  schedule in one transaction. @param actingOperatorKey the calling
   *  operator key's public id (attribution) — it must exist, the history row
   *  references it (HTTP callers always satisfy this via the resolved
   *  authenticated key). @return false when the merchant is unknown. */
  boolean updateFeeSchedule(UUID publicId, FeeSchedule fee, UUID actingOperatorKey);
}
