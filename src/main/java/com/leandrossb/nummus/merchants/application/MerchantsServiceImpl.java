package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.audit.application.OperatorAudit;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantsServiceImpl implements MerchantsService {

  private final MerchantStore store;
  private final OperatorAudit audit;

  public MerchantsServiceImpl(MerchantStore store, OperatorAudit audit) {
    this.store = store;
    this.audit = audit;
  }

  @Override
  @Transactional
  public Merchant create(String name, FeeSchedule fee, UUID actingOperatorKey) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Merchant merchant =
        store.insertMerchant(new Merchant(UUID.randomUUID(), name, Instant.now()), fee);
    if (actingOperatorKey != null) {
      audit.record(actingOperatorKey, "merchant.created", "merchant",
          merchant.publicId(), Map.of("name", name));
    }
    return merchant;
  }

  @Override
  public Optional<FeeSchedule> findFeeSchedule(UUID publicId) {
    return store.findFeeSchedule(publicId);
  }

  @Override
  @Transactional
  public boolean updateFeeSchedule(UUID publicId, FeeSchedule fee, UUID actingOperatorKey) {
    store.insertFeeScheduleEntry(publicId, fee, actingOperatorKey);
    return store.updateFeeSchedule(publicId, fee);
  }

  @Override
  public Optional<ResolvedMerchantKey> findByApiKey(String rawKey) {
    if (rawKey == null || !rawKey.startsWith("nummus_sk_")) {
      return Optional.empty();
    }
    String keyHash = sha256Hex(rawKey);
    Optional<ResolvedMerchantKey> resolved = store.findMerchantByKeyHash(keyHash);
    // Observability only: stamping is best-effort and never gates authentication.
    resolved.ifPresent(r -> store.stampApiKeyLastUsed(keyHash));
    return resolved;
  }

  @Override
  public Optional<Merchant> find(UUID publicId) {
    return store.findMerchant(publicId);
  }

  static String sha256Hex(String rawKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
