package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for merchants and their API keys. */
public interface MerchantStore {

  Merchant insertMerchant(Merchant merchant, FeeSchedule fee);

  Optional<Merchant> findMerchant(UUID publicId);

  Optional<FeeSchedule> findFeeSchedule(UUID merchantPublicId);

  /** @return false when the merchant is unknown. */
  boolean updateFeeSchedule(UUID merchantPublicId, FeeSchedule fee);

  /** Stores hash + prefix; the secret never reaches the store. */
  void insertApiKey(UUID merchantPublicId, String keyHash, String prefix);

  /** The ACTIVE key metadata for a hash, if any. */
  Optional<ApiKey> findActiveKeyByHash(String keyHash);

  List<ApiKey> listKeys(UUID merchantPublicId);

  /** @return false when the key is absent or not the merchant's. */
  boolean revokeApiKey(UUID merchantPublicId, UUID keyPublicId);

  /** The merchant and key behind the ACTIVE, unexpired key with this hash. */
  Optional<ResolvedMerchantKey> findMerchantByKeyHash(String keyHash);

  /** Best-effort observability stamp; never gates authentication. */
  void stampApiKeyLastUsed(String keyHash);

  /** Best-effort observability stamp; never gates authentication. */
  void stampOperatorKeyLastUsed(String keyHash);

  /** Stores an operator key hash + prefix; the secret never reaches the store. */
  void insertOperatorKey(String keyHash, String prefix);

  /** The ACTIVE operator key metadata for a hash, if any. */
  Optional<ApiKey> findActiveOperatorKeyByHash(String keyHash);

  List<ApiKey> listOperatorKeys();

  /** @return false when the key is absent or not ACTIVE. */
  boolean revokeOperatorKey(UUID keyPublicId);

  /** Whether any operator key is ACTIVE — the one-time bootstrap is consumed. */
  boolean hasActiveOperatorKey();
}
