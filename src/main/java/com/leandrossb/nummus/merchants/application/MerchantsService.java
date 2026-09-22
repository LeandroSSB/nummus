package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.Optional;
import java.util.UUID;

/** Merchant onboarding and lookup. */
public interface MerchantsService {

  Merchant create(String name, FeeSchedule fee);

  /** Resolves a raw {@code nummus_sk_…} key to its merchant and key; empty for
   *  unknown, revoked, or expired keys. */
  Optional<ResolvedMerchantKey> findByApiKey(String rawKey);

  Optional<Merchant> find(UUID publicId);

  Optional<FeeSchedule> findFeeSchedule(UUID publicId);

  /** Appends an attributed history entry and updates the cached current
   *  schedule in one transaction. @param actingOperatorKey the calling
   *  operator key's public id (attribution). @return false when the merchant
   *  is unknown. */
  boolean updateFeeSchedule(UUID publicId, FeeSchedule fee, UUID actingOperatorKey);
}
