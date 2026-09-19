package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.payments.application.PaymentsService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingest-and-match: fetches the network report and the internal settlements,
 * matches them purely, and persists report + lines immutably — one transaction.
 */
@Service
public class ConciliationService {

  private final SettlementReportSource reportSource;
  private final PaymentsService payments;
  private final ConciliationStore store;

  public ConciliationService(SettlementReportSource reportSource, PaymentsService payments,
      ConciliationStore store) {
    this.reportSource = reportSource;
    this.payments = payments;
    this.store = store;
  }

  @Transactional
  public SettlementReportSummary ingest(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    var report = reportSource.fetch(from, to);
    var internal = payments.listSettlements(from, to);
    var outcome = ReportMatcher.match(report, internal);
    var summary = new SettlementReportSummary(UUID.randomUUID(), from, to,
        outcome.summary().conciled() ? "CONCILED" : "OPEN", outcome.summary().matched(),
        outcome.summary().amountMismatched(), outcome.summary().missingInternal(),
        outcome.summary().missingExternal(), Instant.now());
    store.insert(summary, outcome.lines());
    return summary;
  }
}
