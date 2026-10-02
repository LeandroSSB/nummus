package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.PaymentLimitsEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One attributed limits change, exactly as recorded. */
public record LimitsHistoryResponse(UUID entryId, BigDecimal maxIntentAmount,
    BigDecimal maxPayoutAmount, BigDecimal maxDailyIntentVolume, Instant validFrom,
    UUID createdBy, String createdByLabel) {

  public static LimitsHistoryResponse from(PaymentLimitsEntry entry) {
    return new LimitsHistoryResponse(entry.entryId(), entry.maxIntentAmount(),
        entry.maxPayoutAmount(), entry.maxDailyIntentVolume(), entry.validFrom(),
        entry.createdBy(), entry.createdByLabel());
  }
}
