package com.leandrossb.nummus.psp_simulator.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import com.leandrossb.nummus.payments.application.NetworkRefund;
import com.leandrossb.nummus.payments.application.NetworkTransfer;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.psp_simulator.domain.RefundNotPendingException;
import com.leandrossb.nummus.psp_simulator.domain.TransferNotPendingException;
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

  @Override
  public NetworkTransfer createPayoutTransfer(Money amount, String destinationBankKey) {
    return simulator.createTransfer(amount, destinationBankKey);
  }

  @Override
  public NetworkTransfer getPayoutTransfer(UUID transferPublicId) {
    return simulator.getTransfer(transferPublicId);
  }

  @Override
  public NetworkTransfer cancelPayoutTransfer(UUID transferPublicId) {
    try {
      return simulator.cancelTransfer(transferPublicId);
    } catch (TransferNotPendingException e) {
      // The cancel lost the status-guarded row to a concurrent settlement, or
      // the instruction was already terminal: this port's contract is the
      // post-attempt state, and the expiry resolution branches on it rather
      // than catching.
      return simulator.getTransfer(transferPublicId);
    }
  }

  @Override
  public NetworkRefund createChargeRefund(UUID chargePublicId, Money amount) {
    return simulator.createRefund(chargePublicId, amount);
  }

  @Override
  public NetworkRefund getChargeRefund(UUID refundPublicId) {
    return simulator.getRefund(refundPublicId);
  }

  @Override
  public NetworkRefund cancelChargeRefund(UUID refundPublicId) {
    try {
      return simulator.cancelRefund(refundPublicId);
    } catch (RefundNotPendingException e) {
      // Post-attempt semantics as cancelPayoutTransfer — the observed state
      // when the cancel did not win.
      return simulator.getRefund(refundPublicId);
    }
  }
}
