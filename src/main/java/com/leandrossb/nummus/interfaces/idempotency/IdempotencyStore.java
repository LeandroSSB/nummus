package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the idempotency layer. Keys are namespaced per caller:
 * a null {@code merchantPublicId} is the operator namespace, a merchant's
 * public id is that merchant's private namespace. {@link #insert} relies on
 * the per-namespace unique indexes to lose races — losers receive Spring's
 * {@code DuplicateKeyException} and switch to the replay path.
 */
public interface IdempotencyStore {

  /** Reserves a slot in the caller's namespace (null merchant = operator). */
  void insert(UUID merchantPublicId, String key, byte[] requestFingerprint, Instant expiresAt);

  /** Looks the slot up in the caller's namespace — never another merchant's. */
  Optional<StoredRow> findByKey(UUID merchantPublicId, String key);

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
