package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.conciliation.application.DuplicateSettlementLinesException;
import com.leandrossb.nummus.conciliation.application.MatchedLine;
import com.leandrossb.nummus.conciliation.application.NetworkSettlement;
import com.leandrossb.nummus.conciliation.application.ReportMatcher;
import com.leandrossb.nummus.conciliation.application.SettlementReport;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.SettlementView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportMatcherTest {

  private static final Instant FROM = Instant.parse("2026-09-18T10:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-18T11:00:00Z");

  private static SettlementView settled(UUID chargeId, String amount) {
    return new SettlementView(UUID.randomUUID(), UUID.randomUUID(), chargeId,
        Money.ofBrl(amount), FROM.plusSeconds(60), UUID.randomUUID());
  }

  @Test
  void matchedMismatchedAndMissingInternalPerExternalLine() {
    var ok = UUID.randomUUID();
    var wrong = UUID.randomUUID();
    var ghost = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        new NetworkSettlement(ok, Money.ofBrl("10.0000"), FROM.plusSeconds(10)),
        new NetworkSettlement(wrong, Money.ofBrl("11.0000"), FROM.plusSeconds(20)),
        new NetworkSettlement(ghost, Money.ofBrl("12.0000"), FROM.plusSeconds(30))));

    var outcome = ReportMatcher.match(report, List.of(
        settled(ok, "10.0"),            // scale differs on purpose: compareTo equality
        settled(wrong, "10.0000")));    // amounts differ

    assertEquals(3, outcome.lines().size());
    assertEquals("MATCHED", statusOf(outcome, ok));
    assertEquals("AMOUNT_MISMATCH", statusOf(outcome, wrong));
    assertEquals("MISSING_INTERNAL", statusOf(outcome, ghost));
    assertEquals(new com.leandrossb.nummus.conciliation.application.MatchSummary(1, 1, 1, 0, false),
        outcome.summary());
  }

  @Test
  void internalSettlementsAbsentFromTheReportAreMissingExternal() {
    var inReport = UUID.randomUUID();
    var absent = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO,
        List.of(new NetworkSettlement(inReport, Money.ofBrl("5.0000"), FROM.plusSeconds(10))));

    var outcome = ReportMatcher.match(report, List.of(settled(inReport, "5.0000"), settled(absent, "9.0000")));

    assertEquals(2, outcome.lines().size());
    assertEquals("MISSING_EXTERNAL", statusOf(outcome, absent));
    assertEquals("INTERNAL", originOf(outcome, absent));
    assertEquals(new com.leandrossb.nummus.conciliation.application.MatchSummary(1, 0, 0, 1, false),
        outcome.summary());
  }

  @Test
  void fullyMatchedReportsAreConciled() {
    var charge = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO,
        List.of(new NetworkSettlement(charge, Money.ofBrl("5.0000"), FROM.plusSeconds(10))));

    var outcome = ReportMatcher.match(report, List.of(settled(charge, "5.0000")));

    assertEquals(1, outcome.lines().size());
    assertEquals("MATCHED", statusOf(outcome, charge));
    assertTrue(outcome.summary().conciled());
    assertEquals(0, outcome.summary().missingExternal());
  }

  @Test
  void duplicateReportLinesAreRejected() {
    var charge = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        new NetworkSettlement(charge, Money.ofBrl("5.0000"), FROM.plusSeconds(10)),
        new NetworkSettlement(charge, Money.ofBrl("5.0000"), FROM.plusSeconds(20))));

    var duplicate = assertThrows(DuplicateSettlementLinesException.class,
        () -> ReportMatcher.match(report, List.of()));
    assertTrue(duplicate.getMessage().contains(charge.toString()));
  }

  private static String statusOf(ReportMatcher.MatchOutcome outcome, UUID charge) {
    return outcome.lines().stream().filter(l -> l.chargePublicId().equals(charge))
        .findFirst().orElseThrow().matchStatus();
  }

  private static String originOf(ReportMatcher.MatchOutcome outcome, UUID charge) {
    return outcome.lines().stream().filter(l -> l.chargePublicId().equals(charge))
        .findFirst().orElseThrow().origin();
  }
}
