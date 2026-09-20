package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.IssuedApiKey;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.time.Instant;
import java.util.UUID;

/** Merchant creation: the FIRST API key's secret appears here and nowhere else. */
public record CreateMerchantResponse(
    UUID merchantId, String name, Instant createdAt, CreateKeyResponse apiKey, FeeResponse fee) {

  public static CreateMerchantResponse from(Merchant merchant, IssuedApiKey firstKey, FeeSchedule fee) {
    return new CreateMerchantResponse(merchant.publicId(), merchant.name(), merchant.createdAt(),
        CreateKeyResponse.from(firstKey), FeeResponse.from(fee));
  }
}
