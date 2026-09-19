package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MerchantsServiceImpl implements MerchantsService {

  private final MerchantStore store;

  public MerchantsServiceImpl(MerchantStore store) {
    this.store = store;
  }

  @Override
  public Merchant create(String name, FeeSchedule fee) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    return store.insertMerchant(new Merchant(UUID.randomUUID(), name, Instant.now()), fee);
  }

  @Override
  public Optional<FeeSchedule> findFeeSchedule(UUID publicId) {
    return store.findFeeSchedule(publicId);
  }

  @Override
  public boolean updateFeeSchedule(UUID publicId, FeeSchedule fee) {
    return store.updateFeeSchedule(publicId, fee);
  }

  @Override
  public Optional<Merchant> findByApiKey(String rawKey) {
    if (rawKey == null || !rawKey.startsWith("nummus_sk_")) {
      return Optional.empty();
    }
    return store.findMerchantByKeyHash(sha256Hex(rawKey));
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
