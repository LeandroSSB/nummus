package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator key lifecycle. Same secret mechanics as merchant keys: 256-bit
 * base64url secret, prefixed, shown exactly once; SHA-256 hash at rest.
 * The first key bootstraps one-time from the configured deployment token.
 */
@Service
public class OperatorKeysServiceImpl implements OperatorKeysService {

  private static final String PREFIX = "nummus_sk_";

  private final MerchantStore store;
  private final OperatorBootstrapProperties bootstrapProperties;
  private final SecureRandom random = new SecureRandom();

  public OperatorKeysServiceImpl(MerchantStore store,
      OperatorBootstrapProperties bootstrapProperties) {
    this.store = store;
    this.bootstrapProperties = bootstrapProperties;
  }

  @Override
  @Transactional
  public IssuedApiKey create(Duration expiresIn) {
    return mint(expiresIn);
  }

  @Override
  public List<ApiKey> list() {
    return store.listOperatorKeys();
  }

  @Override
  @Transactional
  public void revoke(UUID keyPublicId) {
    Objects.requireNonNull(keyPublicId, "keyPublicId must not be null");
    if (!store.revokeOperatorKey(keyPublicId)) {
      throw new UnknownApiKeyException(keyPublicId);
    }
  }

  @Override
  public Optional<ApiKey> findByRawKey(String rawKey) {
    if (rawKey == null || !rawKey.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String keyHash = MerchantsServiceImpl.sha256Hex(rawKey);
    Optional<ApiKey> key = store.findActiveOperatorKeyByHash(keyHash);
    // Observability only: stamping is best-effort and never gates authentication.
    key.ifPresent(k -> store.stampOperatorKeyLastUsed(keyHash));
    return key;
  }

  @Override
  @Transactional
  public IssuedApiKey bootstrap(String presentedToken) {
    String configured = bootstrapProperties.bootstrapToken();
    if (configured == null || configured.isBlank()) {
      throw new BootstrapUnavailableException();
    }
    if (store.hasActiveOperatorKey()) {
      throw new BootstrapAlreadyUsedException();
    }
    if (presentedToken == null
        || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
            presentedToken.getBytes(StandardCharsets.UTF_8))) {
      throw new InvalidBootstrapTokenException();
    }
    return mint(null);
  }

  private IssuedApiKey mint(Duration expiresIn) {
    ApiKeysServiceImpl.requirePositiveExpiry(expiresIn);
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    String keyHash = MerchantsServiceImpl.sha256Hex(rawKey);
    store.insertOperatorKey(keyHash, rawKey.substring(0, 12), expiresIn);
    // The store owns key identity; read the persisted row back so the returned
    // metadata (public_id, created_at, expires_at) is what revoke/list will match on.
    ApiKey stored = store.findActiveOperatorKeyByHash(keyHash)
        .orElseThrow(() -> new IllegalStateException("operator key row missing after insert"));
    return new IssuedApiKey(stored, rawKey);
  }
}
