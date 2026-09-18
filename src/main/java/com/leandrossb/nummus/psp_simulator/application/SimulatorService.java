package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import java.util.UUID;

/** The simulated network's own operations: create, inspect, and act as the payer. */
public interface SimulatorService {

  NetworkCharge create(Money amount);

  NetworkCharge get(UUID publicId);

  /** Payer pays the charge: PENDING → SUCCEEDED (terminal). */
  NetworkCharge pay(UUID publicId);

  /** Payer abandons/fails the charge: PENDING → FAILED (terminal). */
  NetworkCharge fail(UUID publicId);
}
