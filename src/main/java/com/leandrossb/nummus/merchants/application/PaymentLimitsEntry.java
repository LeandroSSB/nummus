package com.leandrossb.nummus.merchants.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One attributed payment-limits change from the merchant's append-only
 *  history, newest first in listings. createdBy — and with it
 *  createdByLabel — is null only for a migration backfill (none exists at
 *  introduction; the field mirrors FeeHistoryEntry's sentinel semantics). */
public record PaymentLimitsEntry(UUID entryId, BigDecimal maxIntentAmount,
    BigDecimal maxPayoutAmount, BigDecimal maxDailyIntentVolume, Instant validFrom,
    UUID createdBy, String createdByLabel) {
}
