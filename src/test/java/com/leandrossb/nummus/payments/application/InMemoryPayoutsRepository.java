package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory fake mirroring the payout repository's status-guarded transitions. */
public class InMemoryPayoutsRepository implements PayoutsRepository {

  private final Map<UUID, Payout> payouts = new ConcurrentHashMap<>();
  private final Map<UUID, Long> sequence = new ConcurrentHashMap<>();
  private final AtomicLong nextId = new AtomicLong();

  @Override
  public Payout insert(Payout payout) {
    payouts.put(payout.publicId(), payout);
    sequence.putIfAbsent(payout.publicId(), nextId.incrementAndGet());
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

  @Override
  public List<Payout> findSettledBetween(Instant from, Instant to) {
    return payouts.values().stream()
        .filter(p -> p.status() == PayoutStatus.SETTLED)
        .filter(p -> !p.settledAt().isBefore(from) && p.settledAt().isBefore(to))
        .sorted(Comparator.comparing(Payout::settledAt))
        .toList();
  }

  @Override
  public List<Payout> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit) {
    Long afterId = after == null ? null : sequence.get(after);
    if (after != null && afterId == null) {
      // An unknown cursor matches nothing, exactly as the SQL subselect does.
      return List.of();
    }
    return payouts.values().stream()
        .filter(p -> accountPublicIds.contains(p.accountPublicId()))
        .filter(p -> status == null || p.status().name().equals(status))
        .filter(p -> account == null || p.accountPublicId().equals(account))
        .filter(p -> afterId == null || sequence.get(p.publicId()) < afterId)
        .sorted(Comparator.comparingLong((Payout p) -> sequence.get(p.publicId())).reversed())
        .limit(limit)
        .toList();
  }

  private synchronized boolean guarded(UUID publicId, PayoutStatus target, UUID executeTx,
      Instant settledAt, Money feeAmount, UUID returnTx) {
    var current = payouts.get(publicId);
    if (current == null || current.status() != PayoutStatus.REQUESTED) {
      return false;
    }
    payouts.put(publicId, new Payout(current.publicId(), current.accountPublicId(),
        current.amount(), target, current.destinationBankKey(), current.bankAccountPublicId(),
        current.transferPublicId(),
        current.expiresAt(), current.createdAt(), settledAt,
        target == PayoutStatus.SETTLED ? feeAmount : null, current.requestTransactionPublicId(),
        executeTx, returnTx));
    return true;
  }

  /** Test driver: move a payout's expiry into the past. */
  public void agePastExpiry(UUID publicId) {
    payouts.computeIfPresent(publicId, (id, payout) -> new Payout(payout.publicId(),
        payout.accountPublicId(), payout.amount(), payout.status(), payout.destinationBankKey(),
        payout.bankAccountPublicId(), payout.transferPublicId(),
        Instant.now().minusSeconds(1), payout.createdAt(),
        payout.settledAt(), payout.feeAmount(), payout.requestTransactionPublicId(),
        payout.executeTransactionPublicId(), payout.returnTransactionPublicId()));
  }
}
