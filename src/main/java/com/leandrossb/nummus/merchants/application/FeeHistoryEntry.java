package com.leandrossb.nummus.merchants.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One attributed fee-schedule change from the merchant's append-only
 *  history, newest first in listings. createdBy — and with it createdByLabel
 *  — is null only for the migration backfill: the pre-attribution sentinel
 *  rendered "system" on the REST surface. */
public record FeeHistoryEntry(UUID entryId, BigDecimal rate, BigDecimal fixed,
    BigDecimal payoutFixed, Instant validFrom, UUID createdBy, String createdByLabel) {
}
