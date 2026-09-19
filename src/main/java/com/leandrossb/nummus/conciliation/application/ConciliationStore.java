package com.leandrossb.nummus.conciliation.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Write-once persistence for conciliation reports. {@link #insert} joins the
 * caller's transaction — the report and its lines commit together or not at all.
 */
public interface ConciliationStore {

  void insert(SettlementReportSummary summary, List<MatchedLine> lines);

  /** Newest first. */
  List<SettlementReportSummary> listSummaries(int limit);

  Optional<SettlementReportSummary> findSummary(UUID publicId);

  /** Lines of one report, insertion order. */
  List<MatchedLine> findLines(UUID reportPublicId);
}
