package com.leandrossb.nummus.psp_simulator.interfaces;

import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.psp_simulator.interfaces.dto.ChargeResponse;
import com.leandrossb.nummus.psp_simulator.interfaces.dto.RefundResponse;
import com.leandrossb.nummus.psp_simulator.interfaces.dto.TransferResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The external world's surface: create charges and transfers indirectly via
 * PaymentNetwork, act as the payer or the destination bank here.
 */
@RestController
@RequestMapping("/simulator")
class SimulatorController {

  private final SimulatorService simulator;

  SimulatorController(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @GetMapping("/charges/{id}")
  ChargeResponse get(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.get(id));
  }

  @PostMapping("/charges/{id}/pay")
  ChargeResponse pay(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.pay(id));
  }

  @PostMapping("/charges/{id}/fail")
  ChargeResponse fail(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.fail(id));
  }

  @GetMapping("/transfers/{id}")
  TransferResponse getTransfer(@PathVariable UUID id) {
    return TransferResponse.from(simulator.getTransfer(id));
  }

  @PostMapping("/transfers/{id}/pay")
  TransferResponse payTransfer(@PathVariable UUID id) {
    return TransferResponse.from(simulator.payTransfer(id));
  }

  @PostMapping("/transfers/{id}/fail")
  TransferResponse failTransfer(@PathVariable UUID id) {
    return TransferResponse.from(simulator.failTransfer(id));
  }

  @PostMapping("/transfers/{id}/cancel")
  TransferResponse cancelTransfer(@PathVariable UUID id) {
    return TransferResponse.from(simulator.cancelTransfer(id));
  }

  @GetMapping("/refunds/{id}")
  RefundResponse getRefund(@PathVariable UUID id) {
    return RefundResponse.from(simulator.getRefund(id));
  }

  @PostMapping("/refunds/{id}/pay")
  RefundResponse payRefund(@PathVariable UUID id) {
    return RefundResponse.from(simulator.payRefund(id));
  }

  @PostMapping("/refunds/{id}/fail")
  RefundResponse failRefund(@PathVariable UUID id) {
    return RefundResponse.from(simulator.failRefund(id));
  }

  @PostMapping("/refunds/{id}/cancel")
  RefundResponse cancelRefund(@PathVariable UUID id) {
    return RefundResponse.from(simulator.cancelRefund(id));
  }
}
