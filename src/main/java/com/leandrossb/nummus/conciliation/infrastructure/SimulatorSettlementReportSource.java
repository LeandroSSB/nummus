package com.leandrossb.nummus.conciliation.infrastructure;

import com.leandrossb.nummus.conciliation.application.NetworkSettlement;
import com.leandrossb.nummus.conciliation.application.SettlementReport;
import com.leandrossb.nummus.conciliation.application.SettlementReportSource;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The report source adapter: the simulated network plays the external PSP. */
@Component
public class SimulatorSettlementReportSource implements SettlementReportSource {

  private final SimulatorService simulator;

  public SimulatorSettlementReportSource(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @Override
  public SettlementReport fetch(Instant from, Instant to) {
    var lines = simulator.settlementReport(from, to).stream()
        .map(settlement -> new NetworkSettlement(settlement.chargePublicId(),
            settlement.amount(), settlement.settledAt()))
        .toList();
    return new SettlementReport(from, to, lines);
  }
}
