package com.leandrossb.nummus.psp_simulator.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The simulated network as seen through the payments module's port — the same
 * bean shape a real PSP adapter would take.
 */
@Component
public class SimulatorPaymentNetwork implements PaymentNetwork {

  private final SimulatorService simulator;

  public SimulatorPaymentNetwork(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @Override
  public NetworkCharge createCharge(Money amount) {
    return simulator.create(amount);
  }

  @Override
  public NetworkCharge getCharge(UUID chargePublicId) {
    return simulator.get(chargePublicId);
  }
}
