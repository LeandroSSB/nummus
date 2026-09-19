package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for merchants and their API keys. */
public interface MerchantStore {

  Merchant insertMerchant(Merchant merchant);

  Optional<Merchant> findMerchant(UUID publicId);

  /** Stores hash + prefix; the secret never reaches the store. */
  void insertApiKey(UUID merchantPublicId, String keyHash, String prefix);

  /** The ACTIVE key metadata for a hash, if any. */
  Optional<ApiKey> findActiveKeyByHash(String keyHash);

  List<ApiKey> listKeys(UUID merchantPublicId);

  /** @return false when the key is absent or not the merchant's. */
  boolean revokeApiKey(UUID merchantPublicId, UUID keyPublicId);

  /** The merchant owning the ACTIVE key with this hash. */
  Optional<Merchant> findMerchantByKeyHash(String keyHash);
}
