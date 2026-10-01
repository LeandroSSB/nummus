package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory fake mirroring the repository's status-guarded transitions. */
public class InMemoryPaymentsRepository implements PaymentsRepository {

  private final Map<UUID, PaymentIntent> intents = new ConcurrentHashMap<>();
  private final Map<UUID, Long> sequence = new ConcurrentHashMap<>();
  private final AtomicLong nextId = new AtomicLong();

  @Override
  public PaymentIntent insert(PaymentIntent intent) {
    intents.put(intent.publicId(), intent);
    sequence.putIfAbsent(intent.publicId(), nextId.incrementAndGet());
    return intent;
  }

  @Override
  public Optional<PaymentIntent> findByPublicId(UUID publicId) {
    return Optional.ofNullable(intents.get(publicId));
  }

  @Override
  public boolean transitionToExpired(UUID publicId) {
    return guarded(publicId, IntentStatus.EXPIRED, null, null, null);
  }

  @Override
  public boolean transitionToVoided(UUID publicId) {
    return guarded(publicId, IntentStatus.VOIDED, null, null, null);
  }

  @Override
  public boolean transitionToFailed(UUID publicId) {
    return guarded(publicId, IntentStatus.FAILED, null, null, null);
  }

  @Override
  public boolean markSettled(UUID publicId, UUID journalTransactionPublicId, Instant settledAt,
      Money feeAmount) {
    return guarded(publicId, IntentStatus.SETTLED, journalTransactionPublicId, settledAt, feeAmount);
  }

  @Override
  public List<PaymentIntent> findSettledBetween(Instant from, Instant to) {
    return intents.values().stream()
        .filter(i -> i.status() == IntentStatus.SETTLED)
        .filter(i -> !i.settledAt().isBefore(from) && i.settledAt().isBefore(to))
        .sorted(java.util.Comparator.comparing(PaymentIntent::settledAt))
        .toList();
  }

  @Override
  public List<PaymentIntent> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit) {
    Long afterId = after == null ? null : sequence.get(after);
    if (after != null && afterId == null) {
      // An unknown cursor matches nothing, exactly as the SQL subselect does.
      return List.of();
    }
    return intents.values().stream()
        .filter(i -> accountPublicIds.contains(i.accountPublicId()))
        .filter(i -> status == null || i.status().name().equals(status))
        .filter(i -> account == null || i.accountPublicId().equals(account))
        .filter(i -> afterId == null || sequence.get(i.publicId()) < afterId)
        .sorted(java.util.Comparator
            .comparingLong((PaymentIntent i) -> sequence.get(i.publicId())).reversed())
        .limit(limit)
        .toList();
  }

  private synchronized boolean guarded(UUID publicId, IntentStatus target, UUID journalTx,
      Instant at, Money feeAmount) {
    var current = intents.get(publicId);
    if (current == null || current.status() != IntentStatus.CREATED) {
      return false;
    }
    intents.put(publicId, new PaymentIntent(current.publicId(), current.accountPublicId(),
        current.amount(), target, current.chargePublicId(), current.expiresAt(),
        current.createdAt(), at, journalTx, target == IntentStatus.SETTLED ? feeAmount : null));
    return true;
  }

  /** Test driver: move an intent's expiry into the past. */
  public void agePastExpiry(UUID publicId) {
    intents.computeIfPresent(publicId, (id, intent) -> new PaymentIntent(
        intent.publicId(), intent.accountPublicId(), intent.amount(), intent.status(),
        intent.chargePublicId(), Instant.now().minusSeconds(1),
        intent.createdAt(), intent.settledAt(), intent.journalTransactionPublicId(),
        intent.feeAmount()));
  }
}
