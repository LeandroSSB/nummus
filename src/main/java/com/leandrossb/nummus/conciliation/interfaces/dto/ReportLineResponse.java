package com.leandrossb.nummus.conciliation.interfaces.dto;

import com.leandrossb.nummus.conciliation.application.MatchedLine;
import java.math.BigDecimal;
import java.util.UUID;

public record ReportLineResponse(
    String origin, String subjectType, UUID subjectId, BigDecimal reportedAmount,
    UUID internalId, BigDecimal internalAmount, String matchStatus) {

  public static ReportLineResponse from(MatchedLine line) {
    return new ReportLineResponse(line.origin(), line.subjectType().name(),
        line.subjectPublicId(),
        line.reportedAmount() == null ? null : line.reportedAmount().amount(),
        line.internalPublicId(),
        line.internalAmount() == null ? null : line.internalAmount().amount(),
        line.matchStatus());
  }
}
