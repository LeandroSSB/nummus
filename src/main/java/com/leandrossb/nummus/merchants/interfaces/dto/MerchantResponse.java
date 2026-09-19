package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.time.Instant;
import java.util.UUID;

/** Merchant view without key material. */
public record MerchantResponse(UUID merchantId, String name, Instant createdAt, FeeResponse fee) {

  public static MerchantResponse from(Merchant merchant, FeeSchedule fee) {
    return new MerchantResponse(merchant.publicId(), merchant.name(), merchant.createdAt(),
        FeeResponse.from(fee));
  }
}
