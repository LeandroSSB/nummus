package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

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
  public IssuedApiKey create(UUID merchantPublicId) {
    Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    UUID keyId = UUID.randomUUID();
    store.insertApiKey(merchantPublicId, MerchantsServiceImpl.sha256Hex(rawKey), rawKey.substring(0, 12));
    return new IssuedApiKey(new ApiKey(keyId, rawKey.substring(0, 12), "ACTIVE", Instant.now()), rawKey);
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
