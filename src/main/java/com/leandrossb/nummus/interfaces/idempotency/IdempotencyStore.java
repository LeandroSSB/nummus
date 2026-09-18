package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port for the idempotency layer. {@link #insert} relies on the
 * unique index on {@code key} to lose races — losers receive Spring's
 * {@code DuplicateKeyException} and switch to the replay path.
 */
public interface IdempotencyStore {

  void insert(String key, byte[] requestFingerprint, Instant expiresAt);

  Optional<StoredRow> findByKey(String key);

  /** Attaches the response to a row that has none yet; false if one is already attached. */
  boolean attachResponse(String key, StoredResponse response);

  /**
   * Claims an expired slot for a new execution: rewrites the fingerprint and
   * expiry and clears any stale response. Returns false when the row is not
   * (or no longer) expired — someone else claimed it first.
   */
  boolean reclaimExpired(String key, byte[] newFingerprint, Instant newExpiresAt);

  /** Deletes every row past its expiry; returns the number of rows removed. */
  int purgeExpired(Instant now);
}
