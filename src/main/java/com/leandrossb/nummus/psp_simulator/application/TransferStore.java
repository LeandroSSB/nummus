package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedTransfer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the simulated network's outbound transfers. Transitions are status-guarded. */
public interface TransferStore {

  SimulatedTransfer insert(SimulatedTransfer transfer);

  Optional<SimulatedTransfer> findByPublicId(UUID publicId);

  /** @return false when the transfer does not exist or is not PENDING. */
  boolean transition(UUID publicId, ChargeStatus target);

  /** SUCCEEDED transfers with updated_at in [from, to), ordered by updated_at then id. */
  List<SimulatedTransfer> findSucceededBetween(Instant from, Instant to);
}
