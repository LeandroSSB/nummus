package com.leandrossb.nummus.psp_simulator.interfaces;

import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.psp_simulator.interfaces.dto.ChargeResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The external world's surface: create charges indirectly via PaymentNetwork, act as the payer here. */
@RestController
@RequestMapping("/simulator/charges")
class SimulatorController {

  private final SimulatorService simulator;

  SimulatorController(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @GetMapping("/{id}")
  ChargeResponse get(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.get(id));
  }

  @PostMapping("/{id}/pay")
  ChargeResponse pay(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.pay(id));
  }

  @PostMapping("/{id}/fail")
  ChargeResponse fail(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.fail(id));
  }
}
