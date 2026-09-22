package com.leandrossb.nummus.conciliation.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Write-once persistence for conciliation reports. {@link #insert} joins the
 * caller's transaction — the report and its lines commit together or not at all.
 * The window-marker methods back the scheduled tumbling re-ingest.
 */
public interface ConciliationStore {

  void insert(SettlementReportSummary summary, List<MatchedLine> lines);

  /** Newest first. */
  List<SettlementReportSummary> listSummaries(int limit);

  Optional<SettlementReportSummary> findSummary(UUID publicId);

  /** Lines of one report, insertion order. */
  List<MatchedLine> findLines(UUID reportPublicId);

  /** The latest report's period_to, if any — the stall detector's input. */
  Optional<Instant> latestReportEnd();

  /** greatest(ingest_state.last_window_end, max(settlement_report.period_to)):
   *  manual ingests that covered pending territory are never re-covered. */
  Instant selfHealingWindowStart();

  /** The DB clock minus the lag — the window end. */
  Instant currentWindowEnd(Duration lag);

  /** Advances the marker; called only with the window end that was ingested. */
  void advanceWindowEnd(Instant end);
}
