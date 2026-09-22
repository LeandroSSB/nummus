package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Operator key lifecycle: issue, list, revoke, and the one-time bootstrap.
 *  Every operator key carries an immutable audit label, and every lifecycle
 *  action lands in the operator audit log attributed to the calling key. */
public interface OperatorKeysService {

  /** @param label the key's immutable identity (1-64 characters);
   *  @param expiresIn optional lifetime; null = never expires.
   *  @param actorKey the calling operator key's public id — the audit entry's
   *  actor. Null records no entry (an action without an attributable caller);
   *  the HTTP surface always resolves and passes the authenticated key.
   *  @throws InvalidOperatorLabelException when the label is blank or over 64 characters.
   *  @throws InvalidKeyExpiryException when expiresIn is non-positive. */
  IssuedApiKey create(String label, Duration expiresIn, UUID actorKey);

  List<ApiKey> list();

  /** @param actorKey the calling operator key's public id (audit actor); null
   *  records no entry.
   *  @throws UnknownApiKeyException when the key is absent or already revoked. */
  void revoke(UUID keyPublicId, UUID actorKey);

  /** Mints a replacement for the calling operator key, carrying the calling
   *  key's label forward; the old key lives until its grace end.
   *  @param actorKey the calling operator key's public id (audit actor); null
   *  records no entry.
   *  @throws UnknownApiKeyException when the key is absent or already revoked. */
  RotatedApiKey rotate(UUID keyPublicId, Duration expiresIn, UUID actorKey);

  /** The ACTIVE operator key for a raw secret, if any. */
  Optional<ApiKey> findByRawKey(String rawKey);

  /**
   * Mints the first operator key from the configured deployment token.
   * @param label the key's immutable identity (1-64 characters).
   * @throws InvalidOperatorLabelException when the label is blank or over 64
   *         characters (checked before any bootstrap-state inspection);
   *         BootstrapUnavailableException when no token is configured;
   *         BootstrapAlreadyUsedException when an ACTIVE operator key exists;
   *         InvalidBootstrapTokenException when the presented token does not match.
   */
  IssuedApiKey bootstrap(String presentedToken, String label);
}
