package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Operator key lifecycle: issue, list, revoke, and the one-time bootstrap.
 *  Every operator key carries an immutable audit label. */
public interface OperatorKeysService {

  /** @param label the key's immutable identity (1-64 characters);
   *  @param expiresIn optional lifetime; null = never expires.
   *  @throws InvalidOperatorLabelException when the label is blank or over 64 characters.
   *  @throws InvalidKeyExpiryException when expiresIn is non-positive. */
  IssuedApiKey create(String label, Duration expiresIn);

  List<ApiKey> list();

  /** @throws UnknownApiKeyException when the key is absent or already revoked. */
  void revoke(UUID keyPublicId);

  /** Mints a replacement for the calling operator key, carrying the calling
   *  key's label forward; the old key lives until its grace end.
   *  @throws UnknownApiKeyException when the key is absent or already revoked. */
  RotatedApiKey rotate(UUID keyPublicId, Duration expiresIn);

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
