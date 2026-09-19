package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.time.Instant;
import java.util.UUID;

/** Merchant view without key material. */
public record MerchantResponse(UUID merchantId, String name, Instant createdAt) {

  public static MerchantResponse from(Merchant merchant) {
    return new MerchantResponse(merchant.publicId(), merchant.name(), merchant.createdAt());
  }
}
