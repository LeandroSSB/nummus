package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.payments.application.PaymentsService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingest-and-match: fetches the network report and the internal settlements,
 * matches them purely, and persists report + lines immutably — one transaction.
 * An OPEN tally pushes its report-open digest through the same transaction, so
 * the alert commits with the report or not at all. The manual path always
 * persists; the scheduled path skips a window that carried no lines at all.
 */
@Service
public class ConciliationService {

  private final SettlementReportSource reportSource;
  private final PaymentsService payments;
  private final ConciliationStore store;
  private final ConciliationAlerts alerts;

  public ConciliationService(SettlementReportSource reportSource, PaymentsService payments,
      ConciliationStore store, ConciliationAlerts alerts) {
    this.reportSource = reportSource;
    this.payments = payments;
    this.store = store;
    this.alerts = alerts;
  }

  /** Manual ingest: an operator asking for a window always gets the report
   *  back, even a fully quiet one. */
  @Transactional
  public SettlementReportSummary ingest(Instant from, Instant to) {
    return doIngest(from, to).summary();
  }

  /** Scheduled path: an empty window (zero lines both sides) persists nothing. */
  @Transactional
  public Optional<SettlementReportSummary> ingestIfAnyLines(Instant from, Instant to) {
    var outcome = doIngest(from, to);
    return outcome.empty() ? Optional.empty() : Optional.of(outcome.summary());
  }

  private IngestOutcome doIngest(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    var report = reportSource.fetch(from, to);
    var internal = payments.listSettlements(from, to);
    if (report.lines().isEmpty() && internal.isEmpty()) {
      return new IngestOutcome(true, null);
    }
    var outcome = ReportMatcher.match(report, internal);
    var summary = new SettlementReportSummary(UUID.randomUUID(), from, to,
        outcome.summary().conciled() ? "CONCILED" : "OPEN", outcome.summary().matched(),
        outcome.summary().amountMismatched(), outcome.summary().missingInternal(),
        outcome.summary().missingExternal(), Instant.now());
    store.insert(summary, outcome.lines());
    if ("OPEN".equals(summary.status())) {
      alerts.reportOpen(summary.publicId(), from, to, summary.matched(),
          summary.amountMismatched(), summary.missingInternal(), summary.missingExternal());
    }
    return new IngestOutcome(false, summary);
  }

  /** Whether the window carried any lines, and the persisted summary when it did. */
  private record IngestOutcome(boolean empty, SettlementReportSummary summary) {
  }
}
