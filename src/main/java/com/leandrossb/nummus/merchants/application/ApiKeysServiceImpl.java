package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Key lifecycle. Secrets are 256-bit, base64url, prefixed, shown exactly once. */
@Service
public class ApiKeysServiceImpl implements ApiKeysService {

  private static final String PREFIX = "nummus_sk_";

  private final MerchantStore store;
  private final ApiKeyProperties apiKeyProperties;
  private final SecureRandom random = new SecureRandom();

  public ApiKeysServiceImpl(MerchantStore store, ApiKeyProperties apiKeyProperties) {
    this.store = store;
    this.apiKeyProperties = apiKeyProperties;
  }

  @Override
  @Transactional
  public IssuedApiKey create(UUID merchantPublicId, Duration expiresIn) {
    Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
    requirePositiveExpiry(expiresIn);
    return mint(merchantPublicId, expiresIn);
  }

  private IssuedApiKey mint(UUID merchantPublicId, Duration expiresIn) {
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    String keyHash = MerchantsServiceImpl.sha256Hex(rawKey);
    store.insertApiKey(merchantPublicId, keyHash, rawKey.substring(0, 12), expiresIn);
    // The store owns key identity; read the persisted row back so the returned
    // metadata (public_id, created_at, expires_at) is what revoke/list will match on.
    ApiKey stored = store.findActiveKeyByHash(keyHash)
        .orElseThrow(() -> new IllegalStateException("api key row missing after insert"));
    return new IssuedApiKey(stored, rawKey);
  }

  static void requirePositiveExpiry(Duration expiresIn) {
    if (expiresIn != null && !expiresIn.isPositive()) {
      throw new InvalidKeyExpiryException();
    }
  }

  @Override
  public List<ApiKey> list(UUID merchantPublicId) {
    return store.listKeys(merchantPublicId);
  }

  @Override
  public void revoke(UUID merchantPublicId, UUID keyPublicId) {
    if (!store.revokeApiKey(merchantPublicId, keyPublicId)) {
      throw new UnknownApiKeyException(keyPublicId);
    }
  }

  @Override
  @Transactional
  public RotatedApiKey rotate(UUID merchantPublicId, UUID keyPublicId, Duration expiresIn) {
    ApiKeysServiceImpl.requirePositiveExpiry(expiresIn);
    IssuedApiKey issued = mint(merchantPublicId, expiresIn);
    Instant oldKeyExpiresAt = store.retireApiKey(merchantPublicId, keyPublicId,
            apiKeyProperties.rotationGrace())
        .orElseThrow(() -> new UnknownApiKeyException(keyPublicId));
    return new RotatedApiKey(issued, oldKeyExpiresAt);
  }
}
