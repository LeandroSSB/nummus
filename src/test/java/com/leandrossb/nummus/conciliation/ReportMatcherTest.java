package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.conciliation.application.DuplicateSettlementLinesException;
import com.leandrossb.nummus.conciliation.application.InternalSettlement;
import com.leandrossb.nummus.conciliation.application.NetworkSettlement;
import com.leandrossb.nummus.conciliation.application.ReportMatcher;
import com.leandrossb.nummus.conciliation.application.SettlementReport;
import com.leandrossb.nummus.conciliation.application.SubjectType;
import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportMatcherTest {

  private static final Instant FROM = Instant.parse("2026-09-18T10:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-18T11:00:00Z");

  private static InternalSettlement settled(SubjectType kind, UUID subjectId, String amount) {
    return new InternalSettlement(kind, UUID.randomUUID(), subjectId,
        Money.ofBrl(amount));
  }

  private static NetworkSettlement line(SubjectType kind, UUID subjectId, String amount,
      Instant at) {
    return new NetworkSettlement(kind, subjectId, Money.ofBrl(amount), at);
  }

  @Test
  void matchedMismatchedAndMissingInternalPerExternalLine() {
    var ok = UUID.randomUUID();
    var wrong = UUID.randomUUID();
    var ghost = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        line(SubjectType.CHARGE, ok, "10.0000", FROM.plusSeconds(10)),
        line(SubjectType.CHARGE, wrong, "11.0000", FROM.plusSeconds(20)),
        line(SubjectType.CHARGE, ghost, "12.0000", FROM.plusSeconds(30))));

    var outcome = ReportMatcher.match(report, List.of(
        settled(SubjectType.CHARGE, ok, "10.0"),            // scale differs on purpose: compareTo equality
        settled(SubjectType.CHARGE, wrong, "10.0000")));    // amounts differ

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
        List.of(line(SubjectType.CHARGE, inReport, "5.0000", FROM.plusSeconds(10))));

    var outcome = ReportMatcher.match(report, List.of(
        settled(SubjectType.CHARGE, inReport, "5.0000"),
        settled(SubjectType.CHARGE, absent, "9.0000")));

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
        List.of(line(SubjectType.CHARGE, charge, "5.0000", FROM.plusSeconds(10))));

    var outcome = ReportMatcher.match(report, List.of(settled(SubjectType.CHARGE, charge, "5.0000")));

    assertEquals(1, outcome.lines().size());
    assertEquals("MATCHED", statusOf(outcome, charge));
    assertTrue(outcome.summary().conciled());
    assertEquals(0, outcome.summary().missingExternal());
  }

  @Test
  void duplicateReportLinesAreRejected() {
    var charge = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        line(SubjectType.CHARGE, charge, "5.0000", FROM.plusSeconds(10)),
        line(SubjectType.CHARGE, charge, "5.0000", FROM.plusSeconds(20))));

    var duplicate = assertThrows(DuplicateSettlementLinesException.class,
        () -> ReportMatcher.match(report, List.of()));
    assertTrue(duplicate.getMessage().contains(charge.toString()));
  }

  @Test
  void sameIdUnderDifferentKindsIsNotADuplicate() {
    var shared = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        line(SubjectType.CHARGE, shared, "5.0000", FROM.plusSeconds(10)),
        line(SubjectType.PAYOUT_TRANSFER, shared, "5.0000", FROM.plusSeconds(20))));

    var outcome = ReportMatcher.match(report, List.of(
        settled(SubjectType.CHARGE, shared, "5.0000"),
        settled(SubjectType.PAYOUT_TRANSFER, shared, "5.0000"),
        settled(SubjectType.CHARGE_REFUND, UUID.randomUUID(), "7.0000"))); // unmatched

    assertEquals(3, outcome.lines().size());
    assertTrue(outcome.summary().conciled() == false);
    assertEquals(1, outcome.summary().missingExternal());
    var orphan = outcome.lines().stream()
        .filter(l -> l.subjectType() == SubjectType.CHARGE_REFUND).findFirst().orElseThrow();
    assertEquals("MISSING_EXTERNAL", orphan.matchStatus());
  }

  @Test
  void duplicateWithinAKindIsRejected() {
    var refund = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        line(SubjectType.CHARGE_REFUND, refund, "5.0000", FROM.plusSeconds(10)),
        line(SubjectType.CHARGE_REFUND, refund, "5.0000", FROM.plusSeconds(20))));

    var duplicate = assertThrows(DuplicateSettlementLinesException.class,
        () -> ReportMatcher.match(report, List.of()));
    assertTrue(duplicate.getMessage().contains(refund.toString()));
  }

  private static String statusOf(ReportMatcher.MatchOutcome outcome, UUID charge) {
    return outcome.lines().stream().filter(l -> l.subjectPublicId().equals(charge))
        .findFirst().orElseThrow().matchStatus();
  }

  private static String originOf(ReportMatcher.MatchOutcome outcome, UUID charge) {
    return outcome.lines().stream().filter(l -> l.subjectPublicId().equals(charge))
        .findFirst().orElseThrow().origin();
  }
}
