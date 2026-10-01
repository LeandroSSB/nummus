package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test double for payments unit scope: no schedule is ever on record, so
 * settlement exercises the {@code FeeSchedule.ZERO} fallback (the two-leg
 * posting), and no cap is ever set, so money movement stays unlimited.
 * Real-schedule behavior is covered by FeeSettlementTest.
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

  @Override
  public Optional<PaymentLimits> findPaymentLimits(UUID publicId) {
    return Optional.of(PaymentLimits.unlimited());
  }

  @Override
  public void updatePaymentLimits(UUID publicId, PaymentLimits limits, UUID actingOperatorKey) {
    // No-op: nothing in payments unit scope reads limits back.
  }

  @Override
  public List<PaymentLimitsEntry> listPaymentLimitsHistory(UUID publicId, UUID after, int limit) {
    return List.of();
  }
}
