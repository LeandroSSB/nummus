package com.leandrossb.nummus.conciliation.interfaces.dto;

import com.leandrossb.nummus.conciliation.application.SettlementReportSummary;
import java.time.Instant;
import java.util.UUID;

public record ReportSummaryResponse(
    UUID reportId, Instant from, Instant to, String status,
    int matched, int amountMismatched, int missingInternal, int missingExternal,
    Instant createdAt) {

  public static ReportSummaryResponse from(SettlementReportSummary summary) {
    return new ReportSummaryResponse(summary.publicId(), summary.from(), summary.to(),
        summary.status(), summary.matched(), summary.amountMismatched(),
        summary.missingInternal(), summary.missingExternal(), summary.createdAt());
  }
}
