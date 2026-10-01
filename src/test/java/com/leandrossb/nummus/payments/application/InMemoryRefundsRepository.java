package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.payments.domain.RefundStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory fake mirroring the refund repository's status-guarded transitions. */
public class InMemoryRefundsRepository implements RefundsRepository {

  private final Map<UUID, Refund> refunds = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> intentAccounts = new ConcurrentHashMap<>();
  private final Map<UUID, Long> sequence = new ConcurrentHashMap<>();
  private final AtomicLong nextId = new AtomicLong();

  @Override
  public Refund insert(Refund refund) {
    refunds.put(refund.publicId(), refund);
    sequence.putIfAbsent(refund.publicId(), nextId.incrementAndGet());
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
  public Map<UUID, Money> findRefundedTotals(List<UUID> intentPublicIds) {
    Map<UUID, Money> totals = new HashMap<>();
    for (UUID intentPublicId : intentPublicIds) {
      refunds.values().stream()
          .filter(refund -> refund.intentPublicId().equals(intentPublicId))
          .filter(refund -> refund.status() == RefundStatus.REQUESTED
              || refund.status() == RefundStatus.SETTLED)
          .map(Refund::amount)
          .reduce(Money::add)
          .ifPresent(total -> totals.put(intentPublicId, total));
    }
    return totals;
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

  @Override
  public List<Refund> findSettledBetween(Instant from, Instant to) {
    return refunds.values().stream()
        .filter(r -> r.status() == RefundStatus.SETTLED)
        .filter(r -> !r.settledAt().isBefore(from) && r.settledAt().isBefore(to))
        .sorted(Comparator.comparing(Refund::settledAt))
        .toList();
  }

  @Override
  public List<Refund> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit) {
    Long afterId = after == null ? null : sequence.get(after);
    if (after != null && afterId == null) {
      // An unknown cursor matches nothing, exactly as the SQL subselect does.
      return List.of();
    }
    return refunds.values().stream()
        .filter(r -> accountOf(r) != null)
        .filter(r -> accountPublicIds.contains(accountOf(r)))
        .filter(r -> status == null || r.status().name().equals(status))
        .filter(r -> account == null || account.equals(accountOf(r)))
        .filter(r -> afterId == null || sequence.get(r.publicId()) < afterId)
        .sorted(Comparator.comparingLong((Refund r) -> sequence.get(r.publicId())).reversed())
        .limit(limit)
        .toList();
  }

  /** The account the refund's owning intent belongs to — the hop the SQL
   *  listing joins through; an intent the fake never learned about owns
   *  nothing, exactly as the inner join drops it. */
  private UUID accountOf(Refund refund) {
    return intentAccounts.get(refund.intentPublicId());
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

  /** Test driver: teach the fake which account an intent belongs to — the
   *  listing's account scope rides this join; the SQL side resolves it from
   *  the intent row itself. */
  public void mapIntentAccount(UUID intentPublicId, UUID accountPublicId) {
    intentAccounts.put(intentPublicId, accountPublicId);
  }
}
