package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** API key lifecycle: issue, list, revoke. */
public interface ApiKeysService {

  /** @param expiresIn optional lifetime; null = never expires.
   *  @throws InvalidKeyExpiryException when expiresIn is non-positive. */
  IssuedApiKey create(UUID merchantPublicId, Duration expiresIn);

  List<ApiKey> list(UUID merchantPublicId);

  /** @throws UnknownApiKeyException when the key is absent or not the merchant's. */
  void revoke(UUID merchantPublicId, UUID keyPublicId);
}
