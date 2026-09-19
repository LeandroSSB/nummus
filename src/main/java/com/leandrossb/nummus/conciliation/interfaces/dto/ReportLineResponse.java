package com.leandrossb.nummus.conciliation.interfaces.dto;

import com.leandrossb.nummus.conciliation.application.MatchedLine;
import java.math.BigDecimal;
import java.util.UUID;

public record ReportLineResponse(
    String origin, UUID chargeId, BigDecimal reportedAmount,
    UUID internalIntentId, BigDecimal internalAmount, String matchStatus) {

  public static ReportLineResponse from(MatchedLine line) {
    return new ReportLineResponse(line.origin(), line.chargePublicId(),
        line.reportedAmount() == null ? null : line.reportedAmount().amount(),
        line.internalIntentPublicId(),
        line.internalAmount() == null ? null : line.internalAmount().amount(),
        line.matchStatus());
  }
}
