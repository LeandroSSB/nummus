package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory network for unit tests: charges, transfers, and
 * refunds stay PENDING until driven.
 */
public class FakePaymentNetwork implements PaymentNetwork {

  private final Map<UUID, NetworkCharge> charges = new ConcurrentHashMap<>();

  private final Map<UUID, NetworkTransfer> transfers = new ConcurrentHashMap<>();

  private final Map<UUID, NetworkRefund> refunds = new ConcurrentHashMap<>();

  @Override
  public NetworkCharge createCharge(Money amount) {
    var charge = new NetworkCharge(UUID.randomUUID(), amount, ChargeStatus.PENDING);
    charges.put(charge.publicId(), charge);
    return charge;
  }

  @Override
  public NetworkCharge getCharge(UUID chargePublicId) {
    return charges.get(chargePublicId);
  }

  /** Test driver: the payer pays. */
  public void succeed(UUID chargePublicId) {
    transition(chargePublicId, ChargeStatus.SUCCEEDED);
  }

  /** Test driver: the payer fails. */
  public void fail(UUID chargePublicId) {
    transition(chargePublicId, ChargeStatus.FAILED);
  }

  /** Test driver: mutate the charge amount — the invariant breach the service must catch. */
  public void mutateAmount(UUID chargePublicId, Money amount) {
    charges.computeIfPresent(chargePublicId,
        (id, charge) -> new NetworkCharge(id, amount, charge.status()));
  }

  private void transition(UUID chargePublicId, ChargeStatus target) {
    charges.computeIfPresent(chargePublicId,
        (id, charge) -> new NetworkCharge(id, charge.amount(), target));
  }

  @Override
  public NetworkTransfer createPayoutTransfer(Money amount, String destinationBankKey) {
    var transfer = new NetworkTransfer(UUID.randomUUID(), amount, destinationBankKey,
        ChargeStatus.PENDING);
    transfers.put(transfer.publicId(), transfer);
    return transfer;
  }

  @Override
  public NetworkTransfer getPayoutTransfer(UUID transferPublicId) {
    return transfers.get(transferPublicId);
  }

  /** The transfer whose cancel races a parallel settlement and loses: the
   * attempt observes the winner's terminal state instead of withdrawing. */
  private volatile UUID cancelLostToSettlement;

  /** Withdraws a pending transfer; a terminal one is returned as observed —
   * no race emulation, the cancel-lost driver is configured per test. */
  @Override
  public NetworkTransfer cancelPayoutTransfer(UUID transferPublicId) {
    transitionTransfer(transferPublicId,
        transferPublicId.equals(cancelLostToSettlement)
            ? ChargeStatus.SUCCEEDED
            : ChargeStatus.CANCELLED);
    return transfers.get(transferPublicId);
  }

  /** Test driver: arm the cancel-lost race — the cancel of this transfer
   * arrives after a parallel pay already won the status-guarded row, so the
   * attempt observes SUCCEEDED rather than withdrawing the instruction. */
  public void loseCancelToSettlement(UUID transferPublicId) {
    cancelLostToSettlement = transferPublicId;
  }

  private void transitionTransfer(UUID transferPublicId, ChargeStatus target) {
    transfers.computeIfPresent(transferPublicId, (id, transfer) ->
        transfer.status() == ChargeStatus.PENDING
            ? new NetworkTransfer(id, transfer.amount(), transfer.destinationBankKey(), target)
            : transfer);
  }

  @Override
  public NetworkRefund createChargeRefund(UUID chargePublicId, Money amount) {
    var refund = new NetworkRefund(UUID.randomUUID(), chargePublicId, amount,
        ChargeStatus.PENDING);
    refunds.put(refund.publicId(), refund);
    return refund;
  }

  @Override
  public NetworkRefund getChargeRefund(UUID refundPublicId) {
    return refunds.get(refundPublicId);
  }

  /** The refund whose cancel races a parallel pay and loses: the attempt
   * observes the winner's terminal state instead of withdrawing. */
  private volatile UUID refundCancelLostToSettlement;

  /** Withdraws a pending refund; a terminal one is returned as observed —
   * no race emulation, the cancel-lost driver is configured per test. */
  @Override
  public NetworkRefund cancelChargeRefund(UUID refundPublicId) {
    transitionRefund(refundPublicId,
        refundPublicId.equals(refundCancelLostToSettlement)
            ? ChargeStatus.SUCCEEDED
            : ChargeStatus.CANCELLED);
    return refunds.get(refundPublicId);
  }

  /** Test driver: arm the refund cancel-lost race — the cancel of this refund
   * arrives after a parallel pay already won the status-guarded row, so the
   * attempt observes SUCCEEDED rather than withdrawing the instruction. */
  public void loseRefundCancelToSettlement(UUID refundPublicId) {
    refundCancelLostToSettlement = refundPublicId;
  }

  private void transitionRefund(UUID refundPublicId, ChargeStatus target) {
    refunds.computeIfPresent(refundPublicId, (id, refund) ->
        refund.status() == ChargeStatus.PENDING
            ? new NetworkRefund(id, refund.chargePublicId(), refund.amount(), target)
            : refund);
  }
}
