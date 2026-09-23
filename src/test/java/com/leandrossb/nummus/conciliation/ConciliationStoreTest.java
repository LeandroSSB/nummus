package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.conciliation.application.ConciliationStore;
import com.leandrossb.nummus.conciliation.application.MatchedLine;
import com.leandrossb.nummus.conciliation.application.SettlementReport;
import com.leandrossb.nummus.conciliation.application.SettlementReportSource;
import com.leandrossb.nummus.conciliation.application.SettlementReportSummary;
import com.leandrossb.nummus.conciliation.application.SubjectType;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConciliationStoreTest extends IntegrationTestBase {

  @Autowired
  private ConciliationStore store;

  @Autowired
  private SettlementReportSource source;

  @Autowired
  private SimulatorService simulator;

  @Test
  void insertAndReadBackReportWithLines() {
    var charge = UUID.randomUUID();
    var intent = UUID.randomUUID();
    var summary = new SettlementReportSummary(UUID.randomUUID(),
        Instant.now().minusSeconds(60), Instant.now(), "OPEN", 1, 0, 0, 1, Instant.now());
    var lines = List.of(
        new MatchedLine("EXTERNAL", SubjectType.CHARGE, charge, Money.ofBrl("10.0000"), intent,
            Money.ofBrl("10.0000"), "MATCHED"),
        new MatchedLine("INTERNAL", SubjectType.PAYOUT_TRANSFER, UUID.randomUUID(), null,
            UUID.randomUUID(), Money.ofBrl("3.0000"), "MISSING_EXTERNAL"));

    store.insert(summary, lines);

    var read = store.findSummary(summary.publicId()).orElseThrow();
    assertEquals("OPEN", read.status());
    assertEquals(1, read.matched());
    assertEquals(1, read.missingExternal());
    assertEquals(1, store.listSummaries(10).stream()
        .filter(s -> s.publicId().equals(summary.publicId())).count());
    var readLines = store.findLines(summary.publicId());
    assertEquals(2, readLines.size());
    assertEquals(SubjectType.CHARGE, readLines.get(0).subjectType());
  }

  @Test
  void sourceFetchesTheSimulatorReportForTheWindow() {
    var paid = simulator.create(Money.ofBrl("4.0000"));
    simulator.pay(paid.publicId());

    var report = source.fetch(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

    assertEquals(1, report.lines().stream()
        .filter(l -> l.subjectPublicId().equals(paid.publicId())).count());
  }
}
