package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.Optional;
import java.util.UUID;

/** Merchant onboarding and lookup. */
public interface MerchantsService {

  Merchant create(String name, FeeSchedule fee);

  /** Resolves a raw {@code nummus_sk_…} key to its merchant; empty for unknown or revoked keys. */
  Optional<Merchant> findByApiKey(String rawKey);

  Optional<Merchant> find(UUID publicId);

  Optional<FeeSchedule> findFeeSchedule(UUID publicId);

  /** @return false when the merchant is unknown. */
  boolean updateFeeSchedule(UUID publicId, FeeSchedule fee);
}
