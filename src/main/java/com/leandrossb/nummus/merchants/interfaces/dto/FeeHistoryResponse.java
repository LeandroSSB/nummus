package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.FeeHistoryEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** REST view of one fee-history entry. A null creator — the migration
 *  backfill's pre-attribution sentinel — renders as {@code "system"}. */
public record FeeHistoryResponse(UUID entryId, BigDecimal rate, BigDecimal fixed,
    BigDecimal payoutFixed, Instant validFrom, UUID createdBy, String createdByLabel) {

  public static FeeHistoryResponse from(FeeHistoryEntry entry) {
    return new FeeHistoryResponse(entry.entryId(), entry.rate(), entry.fixed(),
        entry.payoutFixed(), entry.validFrom(), entry.createdBy(),
        entry.createdByLabel() == null ? "system" : entry.createdByLabel());
  }
}
