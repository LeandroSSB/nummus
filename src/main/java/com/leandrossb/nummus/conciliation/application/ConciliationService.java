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
  private final ConciliationProperties properties;

  public ConciliationService(SettlementReportSource reportSource, PaymentsService payments,
      ConciliationStore store, ConciliationAlerts alerts, ConciliationProperties properties) {
    this.reportSource = reportSource;
    this.payments = payments;
    this.store = store;
    this.alerts = alerts;
    this.properties = properties;
  }

  /** Manual ingest: an operator asking for a window always gets the report
   *  back, even a fully quiet one — unless its end reaches further ahead of
   *  now than the slack allows. A future-dated report here would hold the
   *  scheduled window start past the lagged now and stall every tick. */
  @Transactional
  public SettlementReportSummary ingest(Instant from, Instant to) {
    if (to.isAfter(Instant.now().plus(properties.maxWindowAhead()))) {
      throw new IllegalArgumentException(
          "window end is too far in the future: " + to + " (allowed ahead: "
              + properties.maxWindowAhead() + ")");
    }
    return persist(matchWindow(from, to));
  }

  /** Scheduled path: an empty window (zero lines both sides) persists nothing. */
  @Transactional
  public Optional<SettlementReportSummary> ingestIfAnyLines(Instant from, Instant to) {
    var matched = matchWindow(from, to);
    return matched.empty() ? Optional.empty() : Optional.of(persist(matched));
  }

  /** Fetch-and-match, with emptiness decided before anything is inserted. */
  private MatchedWindow matchWindow(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    var report = reportSource.fetch(from, to);
    var internal = payments.listSettlements(from, to);
    var outcome = ReportMatcher.match(report, internal);
    boolean empty = report.lines().isEmpty() && internal.isEmpty();
    return new MatchedWindow(from, to, outcome, empty);
  }

  /** Persists the report and — when it lands OPEN — pushes its digest, both in
   *  the caller's transaction. */
  private SettlementReportSummary persist(MatchedWindow matched) {
    var tally = matched.outcome().summary();
    var summary = new SettlementReportSummary(UUID.randomUUID(), matched.from(), matched.to(),
        tally.conciled() ? "CONCILED" : "OPEN", tally.matched(), tally.amountMismatched(),
        tally.missingInternal(), tally.missingExternal(), Instant.now());
    store.insert(summary, matched.outcome().lines());
    if ("OPEN".equals(summary.status())) {
      alerts.reportOpen(summary.publicId(), matched.from(), matched.to(), summary.matched(),
          summary.amountMismatched(), summary.missingInternal(), summary.missingExternal());
    }
    return summary;
  }

  /** A matched window: whether it carried any lines at all, and the match to
   *  persist when it did. */
  private record MatchedWindow(Instant from, Instant to, ReportMatcher.MatchOutcome outcome,
      boolean empty) {
  }
}
