package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.security.SecureRandom;
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
  private final SecureRandom random = new SecureRandom();

  public ApiKeysServiceImpl(MerchantStore store) {
    this.store = store;
  }

  @Override
  @Transactional
  public IssuedApiKey create(UUID merchantPublicId) {
    Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    String keyHash = MerchantsServiceImpl.sha256Hex(rawKey);
    store.insertApiKey(merchantPublicId, keyHash, rawKey.substring(0, 12));
    // The store owns key identity; read the persisted row back so the returned
    // metadata (public_id, created_at) is what revoke/list will match on.
    ApiKey stored = store.findActiveKeyByHash(keyHash)
        .orElseThrow(() -> new IllegalStateException("api key row missing after insert"));
    return new IssuedApiKey(stored, rawKey);
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
}
