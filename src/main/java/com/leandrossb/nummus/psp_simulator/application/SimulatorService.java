package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import com.leandrossb.nummus.payments.application.NetworkRefund;
import com.leandrossb.nummus.payments.application.NetworkTransfer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The simulated network's own operations: create, inspect, and act as the payer. */
public interface SimulatorService {

  NetworkCharge create(Money amount);

  NetworkCharge get(UUID publicId);

  /** Payer pays the charge: PENDING → SUCCEEDED (terminal). */
  NetworkCharge pay(UUID publicId);

  /** Payer abandons/fails the charge: PENDING → FAILED (terminal). */
  NetworkCharge fail(UUID publicId);

  /** The network's settlement report: SUCCEEDED charges in [from, to). */
  List<NetworkSettlement> settlementReport(Instant from, Instant to);

  /** Creates an outbound transfer to a destination bank: starts PENDING. */
  NetworkTransfer createTransfer(Money amount, String destinationBankKey);

  NetworkTransfer getTransfer(UUID publicId);

  /** The destination bank settles the transfer: PENDING → SUCCEEDED (terminal). */
  NetworkTransfer payTransfer(UUID publicId);

  /** The destination bank rejects the transfer: PENDING → FAILED (terminal). */
  NetworkTransfer failTransfer(UUID publicId);

  /** The instruction is withdrawn before executing: PENDING → CANCELLED (terminal). */
  NetworkTransfer cancelTransfer(UUID publicId);

  /** Creates a refund instruction on a charge: starts PENDING. The network's
   * own never-over-refund invariant guards the creation — the sum of every
   * refund on the charge, PENDING ones included, may not exceed the charge amount. */
  NetworkRefund createRefund(UUID chargePublicId, Money amount);

  NetworkRefund getRefund(UUID publicId);

  /** The network settles the refund: PENDING → SUCCEEDED (terminal). */
  NetworkRefund payRefund(UUID publicId);

  /** The network rejects the refund: PENDING → FAILED (terminal). */
  NetworkRefund failRefund(UUID publicId);

  /** The instruction is withdrawn before executing: PENDING → CANCELLED (terminal). */
  NetworkRefund cancelRefund(UUID publicId);
}
