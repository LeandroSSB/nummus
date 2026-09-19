package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Operator key lifecycle: issue, list, revoke, and the one-time bootstrap. */
public interface OperatorKeysService {

  IssuedApiKey create();

  List<ApiKey> list();

  /** @throws UnknownApiKeyException when the key is absent or already revoked. */
  void revoke(UUID keyPublicId);

  /** The ACTIVE operator key for a raw secret, if any. */
  Optional<ApiKey> findByRawKey(String rawKey);

  /**
   * Mints the first operator key from the configured deployment token.
   * @throws BootstrapUnavailableException when no token is configured;
   *         BootstrapAlreadyUsedException when an ACTIVE operator key exists;
   *         InvalidBootstrapTokenException when the presented token does not match.
   */
  IssuedApiKey bootstrap(String presentedToken);
}
