package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the payments module. Every state change is status-guarded. */
public interface PaymentsRepository {

  PaymentIntent insert(PaymentIntent intent);

  Optional<PaymentIntent> findByPublicId(UUID publicId);

  /** CREATED → EXPIRED. @return false when the intent is not CREATED. */
  boolean transitionToExpired(UUID publicId);

  /** CREATED → FAILED. @return false when the intent is not CREATED. */
  boolean transitionToFailed(UUID publicId);

  /** CREATED → SETTLED with the exactly-once journal link. @return false when not CREATED. */
  boolean markSettled(UUID publicId, UUID journalTransactionPublicId, Instant settledAt);
}
