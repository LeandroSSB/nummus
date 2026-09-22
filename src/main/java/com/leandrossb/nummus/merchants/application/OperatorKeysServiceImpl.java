package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
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
  private final ApiKeyProperties apiKeyProperties;
  private final SecureRandom random = new SecureRandom();

  public OperatorKeysServiceImpl(MerchantStore store,
      OperatorBootstrapProperties bootstrapProperties, ApiKeyProperties apiKeyProperties) {
    this.store = store;
    this.bootstrapProperties = bootstrapProperties;
    this.apiKeyProperties = apiKeyProperties;
  }

  /** Operator labels are immutable audit identity: 1-64 characters after trim. */
  static void requireValidLabel(String label) {
    if (label == null || label.isBlank() || label.strip().length() > 64) {
      throw new InvalidOperatorLabelException();
    }
  }

  @Override
  @Transactional
  public IssuedApiKey create(String label, Duration expiresIn) {
    return mint(label, expiresIn);
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
  @Transactional
  public RotatedApiKey rotate(UUID keyPublicId, Duration expiresIn) {
    // The replacement inherits the calling key's immutable label.
    String label = store.findOperatorKeyLabel(keyPublicId)
        .orElseThrow(() -> new UnknownApiKeyException(keyPublicId));
    IssuedApiKey issued = mint(label, expiresIn);
    Instant oldKeyExpiresAt = store.retireOperatorKey(keyPublicId,
            apiKeyProperties.rotationGrace())
        .orElseThrow(() -> new UnknownApiKeyException(keyPublicId));
    return new RotatedApiKey(issued, oldKeyExpiresAt);
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
  public IssuedApiKey bootstrap(String presentedToken, String label) {
    // Label validation precedes every bootstrap-state check so a malformed
    // request is a 400 regardless of the deployment's one-time state.
    requireValidLabel(label);
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
    return mint(label, null);
  }

  private IssuedApiKey mint(String label, Duration expiresIn) {
    requireValidLabel(label);
    ApiKeysServiceImpl.requirePositiveExpiry(expiresIn);
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    String keyHash = MerchantsServiceImpl.sha256Hex(rawKey);
    store.insertOperatorKey(keyHash, rawKey.substring(0, 12), expiresIn, label);
    // The store owns key identity; read the persisted row back so the returned
    // metadata (public_id, created_at, expires_at) is what revoke/list will match on.
    ApiKey stored = store.findActiveOperatorKeyByHash(keyHash)
        .orElseThrow(() -> new IllegalStateException("operator key row missing after insert"));
    return new IssuedApiKey(stored, rawKey);
  }
}
