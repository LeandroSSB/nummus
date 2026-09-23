package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedRefund;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the simulated network's charge refunds. Transitions are status-guarded. */
public interface RefundStore {

  SimulatedRefund insert(SimulatedRefund refund);

  Optional<SimulatedRefund> findByPublicId(UUID publicId);

  /** @return false when the refund does not exist or is not PENDING. */
  boolean transition(UUID publicId, ChargeStatus target);

  /** Sum of refund amounts still holding or moving money on the charge — PENDING and
   * SUCCEEDED rows; FAILED refunds are released and never counted. Zero when none. */
  Money totalRefunded(UUID chargePublicId);
}
