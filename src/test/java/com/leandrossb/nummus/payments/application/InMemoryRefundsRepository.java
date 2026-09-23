package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.payments.domain.RefundStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fake mirroring the refund repository's status-guarded transitions. */
public class InMemoryRefundsRepository implements RefundsRepository {

  private final Map<UUID, Refund> refunds = new ConcurrentHashMap<>();

  @Override
  public Refund insert(Refund refund) {
    refunds.put(refund.publicId(), refund);
    return refund;
  }

  @Override
  public Optional<Refund> findByPublicId(UUID publicId) {
    return Optional.ofNullable(refunds.get(publicId));
  }

  @Override
  public Money refundedTotal(UUID intentPublicId) {
    return refunds.values().stream()
        .filter(refund -> refund.intentPublicId().equals(intentPublicId))
        .filter(refund -> refund.status() == RefundStatus.REQUESTED
            || refund.status() == RefundStatus.SETTLED)
        .map(Refund::amount)
        .reduce(Money::add)
        .orElseGet(() -> Money.ofBrl("0.0000"));
  }

  @Override
  public boolean markSettled(UUID publicId, UUID executeTransactionPublicId, Instant settledAt) {
    return guarded(publicId, RefundStatus.SETTLED, executeTransactionPublicId, settledAt, null);
  }

  @Override
  public boolean markFailed(UUID publicId, UUID returnTransactionPublicId) {
    return guarded(publicId, RefundStatus.FAILED, null, null, returnTransactionPublicId);
  }

  @Override
  public boolean markExpired(UUID publicId, UUID returnTransactionPublicId) {
    return guarded(publicId, RefundStatus.EXPIRED, null, null, returnTransactionPublicId);
  }

  private synchronized boolean guarded(UUID publicId, RefundStatus target, UUID executeTx,
      Instant settledAt, UUID returnTx) {
    var current = refunds.get(publicId);
    if (current == null || current.status() != RefundStatus.REQUESTED) {
      return false;
    }
    refunds.put(publicId, new Refund(current.publicId(), current.intentPublicId(),
        current.amount(), target, current.networkRefundPublicId(), current.expiresAt(),
        current.createdAt(), settledAt, current.holdTransactionPublicId(), executeTx, returnTx));
    return true;
  }

  /** Test driver: move a refund's expiry into the past. */
  public void agePastExpiry(UUID publicId) {
    refunds.computeIfPresent(publicId, (id, refund) -> new Refund(refund.publicId(),
        refund.intentPublicId(), refund.amount(), refund.status(), refund.networkRefundPublicId(),
        Instant.now().minusSeconds(1), refund.createdAt(), refund.settledAt(),
        refund.holdTransactionPublicId(), refund.executeTransactionPublicId(),
        refund.returnTransactionPublicId()));
  }
}
