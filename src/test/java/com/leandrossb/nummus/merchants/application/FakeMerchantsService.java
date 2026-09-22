package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.Optional;
import java.util.UUID;

/**
 * Test double for payments unit scope: no schedule is ever on record, so
 * settlement exercises the {@code FeeSchedule.ZERO} fallback (the two-leg
 * posting). Real-schedule behavior is covered by FeeSettlementTest.
 */
public final class FakeMerchantsService implements MerchantsService {

  @Override
  public Merchant create(String name, FeeSchedule fee, UUID actingOperatorKey) {
    throw new UnsupportedOperationException("not used in payments unit scope");
  }

  @Override
  public Optional<ResolvedMerchantKey> findByApiKey(String rawKey) {
    return Optional.empty();
  }

  @Override
  public Optional<Merchant> find(UUID publicId) {
    return Optional.empty();
  }

  @Override
  public Optional<FeeSchedule> findFeeSchedule(UUID publicId) {
    return Optional.empty();
  }

  @Override
  public boolean updateFeeSchedule(UUID publicId, FeeSchedule fee, UUID actingOperatorKey) {
    return false;
  }
}
