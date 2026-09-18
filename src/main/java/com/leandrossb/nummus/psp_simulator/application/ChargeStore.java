package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedCharge;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the simulated network. Transitions are status-guarded. */
public interface ChargeStore {

  SimulatedCharge insert(SimulatedCharge charge);

  Optional<SimulatedCharge> findByPublicId(UUID publicId);

  /** @return false when the charge does not exist or is not PENDING. */
  boolean transition(UUID publicId, ChargeStatus target);
}
