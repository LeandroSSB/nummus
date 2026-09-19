package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedCharge;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the simulated network. Transitions are status-guarded. */
public interface ChargeStore {

  SimulatedCharge insert(SimulatedCharge charge);

  Optional<SimulatedCharge> findByPublicId(UUID publicId);

  /** @return false when the charge does not exist or is not PENDING. */
  boolean transition(UUID publicId, ChargeStatus target);

  /** SUCCEEDED charges with updated_at in [from, to), ordered by updated_at then id. */
  List<SimulatedCharge> findSucceededBetween(Instant from, Instant to);
}
