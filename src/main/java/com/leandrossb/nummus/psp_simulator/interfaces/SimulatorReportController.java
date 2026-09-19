package com.leandrossb.nummus.psp_simulator.interfaces;

import com.leandrossb.nummus.psp_simulator.application.NetworkSettlement;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator surface: the raw settlement report the simulated network emits. */
@RestController
class SimulatorReportController {

  private final SimulatorService simulator;

  SimulatorReportController(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @GetMapping("/simulator/settlement-report")
  List<NetworkSettlementResponse> report(@RequestParam String from, @RequestParam String to) {
    var lines = simulator.settlementReport(Instant.parse(from), Instant.parse(to));
    return lines.stream().map(NetworkSettlementResponse::from).toList();
  }

  record NetworkSettlementResponse(java.util.UUID chargeId, java.math.BigDecimal amount,
      String currency, Instant settledAt) {

    static NetworkSettlementResponse from(NetworkSettlement settlement) {
      return new NetworkSettlementResponse(settlement.chargePublicId(),
          settlement.amount().amount(), settlement.amount().currency().getCurrencyCode(),
          settlement.settledAt());
    }
  }
}
