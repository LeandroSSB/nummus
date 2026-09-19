package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;
import java.util.UUID;

/** The persisted report header with its divergence tally. */
public record SettlementReportSummary(
    UUID publicId, Instant from, Instant to, String status,
    int matched, int amountMismatched, int missingInternal, int missingExternal,
    Instant createdAt) {
}
