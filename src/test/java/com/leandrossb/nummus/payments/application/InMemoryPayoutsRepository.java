package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fake mirroring the payout repository's status-guarded transitions. */
public class InMemoryPayoutsRepository implements PayoutsRepository {

  private final Map<UUID, Payout> payouts = new ConcurrentHashMap<>();

  @Override
  public Payout insert(Payout payout) {
    payouts.put(payout.publicId(), payout);
    return payout;
  }

  @Override
  public Optional<Payout> findByPublicId(UUID publicId) {
    return Optional.ofNullable(payouts.get(publicId));
  }

  @Override
  public boolean markSettled(UUID publicId, UUID executeTransactionPublicId, Instant settledAt,
      Money feeAmount) {
    return guarded(publicId, PayoutStatus.SETTLED, executeTransactionPublicId, settledAt,
        feeAmount, null);
  }

  @Override
  public boolean markFailed(UUID publicId, UUID returnTransactionPublicId) {
    return guarded(publicId, PayoutStatus.FAILED, null, null, null, returnTransactionPublicId);
  }

  @Override
  public boolean markExpired(UUID publicId, UUID returnTransactionPublicId) {
    return guarded(publicId, PayoutStatus.EXPIRED, null, null, null, returnTransactionPublicId);
  }

  private synchronized boolean guarded(UUID publicId, PayoutStatus target, UUID executeTx,
      Instant settledAt, Money feeAmount, UUID returnTx) {
    var current = payouts.get(publicId);
    if (current == null || current.status() != PayoutStatus.REQUESTED) {
      return false;
    }
    payouts.put(publicId, new Payout(current.publicId(), current.accountPublicId(),
        current.amount(), target, current.destinationBankKey(), current.transferPublicId(),
        current.expiresAt(), current.createdAt(), settledAt,
        target == PayoutStatus.SETTLED ? feeAmount : null, current.requestTransactionPublicId(),
        executeTx, returnTx));
    return true;
  }

  /** Test driver: move a payout's expiry into the past. */
  public void agePastExpiry(UUID publicId) {
    payouts.computeIfPresent(publicId, (id, payout) -> new Payout(payout.publicId(),
        payout.accountPublicId(), payout.amount(), payout.status(), payout.destinationBankKey(),
        payout.transferPublicId(), Instant.now().minusSeconds(1), payout.createdAt(),
        payout.settledAt(), payout.feeAmount(), payout.requestTransactionPublicId(),
        payout.executeTransactionPublicId(), payout.returnTransactionPublicId()));
  }
}
