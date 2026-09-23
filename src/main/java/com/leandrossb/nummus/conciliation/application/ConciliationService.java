package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.audit.application.OperatorAudit;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingest-and-match: fetches the network report and the internal settlements,
 * matches them purely, and persists report + lines immutably — one transaction.
 * An OPEN tally pushes its report-open digest through the same transaction, so
 * the alert commits with the report or not at all. The manual path always
 * persists and is attributed to the calling key in the audit log; the
 * scheduled path has no operator, records nothing, and skips a window that
 * carried no lines at all.
 */
@Service
public class ConciliationService {

  private final SettlementReportSource reportSource;
  private final PaymentsService payments;
  private final PayoutsService payouts;
  private final RefundsService refunds;
  private final ConciliationStore store;
  private final ConciliationAlerts alerts;
  private final ConciliationProperties properties;
  private final OperatorAudit audit;

  public ConciliationService(SettlementReportSource reportSource, PaymentsService payments,
      PayoutsService payouts, RefundsService refunds, ConciliationStore store,
      ConciliationAlerts alerts, ConciliationProperties properties, OperatorAudit audit) {
    this.reportSource = reportSource;
    this.payments = payments;
    this.payouts = payouts;
    this.refunds = refunds;
    this.store = store;
    this.alerts = alerts;
    this.properties = properties;
    this.audit = audit;
  }

  /** Manual ingest: an operator asking for a window always gets the report
   *  back, even a fully quiet one — unless its end reaches further ahead of
   *  now than the slack allows. A future-dated report here would hold the
   *  scheduled window start past the lagged now and stall every tick. */
  @Transactional
  public SettlementReportSummary ingest(Instant from, Instant to, UUID actorKey) {
    if (to.isAfter(Instant.now().plus(properties.maxWindowAhead()))) {
      throw new IllegalArgumentException(
          "window end is too far in the future: " + to + " (allowed ahead: "
              + properties.maxWindowAhead() + ")");
    }
    return persist(matchWindow(from, to), actorKey);
  }

  /** Scheduled path: an empty window (zero lines both sides) persists nothing,
   *  and no operator is acting — a tick records no audit entry. */
  @Transactional
  public Optional<SettlementReportSummary> ingestIfAnyLines(Instant from, Instant to) {
    var matched = matchWindow(from, to);
    return matched.empty() ? Optional.empty() : Optional.of(persist(matched, null));
  }

  /** Fetch-and-match, with emptiness decided before anything is inserted. */
  private MatchedWindow matchWindow(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    var report = reportSource.fetch(from, to);
    var internal = new ArrayList<InternalSettlement>();
    payments.listSettlements(from, to).forEach(v -> internal.add(
        new InternalSettlement(SubjectType.CHARGE, v.intentPublicId(), v.chargePublicId(),
            v.amount())));
    payouts.listSettlements(from, to).forEach(v -> internal.add(
        new InternalSettlement(SubjectType.PAYOUT_TRANSFER, v.internalPublicId(),
            v.networkInstructionPublicId(), v.amount())));
    refunds.listSettlements(from, to).forEach(v -> internal.add(
        new InternalSettlement(SubjectType.CHARGE_REFUND, v.internalPublicId(),
            v.networkInstructionPublicId(), v.amount())));
    var outcome = ReportMatcher.match(report, List.copyOf(internal));
    boolean empty = report.lines().isEmpty() && internal.isEmpty();
    return new MatchedWindow(from, to, outcome, empty);
  }

  /** Persists the report and — when it lands OPEN — pushes its digest, both in
   *  the caller's transaction. A non-null actor leaves its audit entry in the
   *  same transaction, carrying the ingested window's bounds. */
  private SettlementReportSummary persist(MatchedWindow matched, UUID actorKey) {
    var tally = matched.outcome().summary();
    var summary = new SettlementReportSummary(UUID.randomUUID(), matched.from(), matched.to(),
        tally.conciled() ? "CONCILED" : "OPEN", tally.matched(), tally.amountMismatched(),
        tally.missingInternal(), tally.missingExternal(), Instant.now());
    store.insert(summary, matched.outcome().lines());
    if (actorKey != null) {
      audit.record(actorKey, "conciliation.ingested", "conciliation_report",
          summary.publicId(),
          Map.of("from", matched.from().toString(), "to", matched.to().toString()));
    }
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
