package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.util.List;
import java.util.UUID;

/** API key lifecycle: issue, list, revoke. */
public interface ApiKeysService {

  IssuedApiKey create(UUID merchantPublicId);

  List<ApiKey> list(UUID merchantPublicId);

  /** @throws UnknownApiKeyException when the key is absent or not the merchant's. */
  void revoke(UUID merchantPublicId, UUID keyPublicId);
}
