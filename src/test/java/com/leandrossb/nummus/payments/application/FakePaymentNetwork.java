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
}
