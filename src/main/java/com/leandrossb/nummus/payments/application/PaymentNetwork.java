package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * The external payment network contract — the same interface a real PSP adapter would
 * implement. The psp-simulator module provides the implementation; production code never
 * mocks this boundary.
 */
public interface PaymentNetwork {

  NetworkCharge createCharge(Money amount);

  NetworkCharge getCharge(UUID chargePublicId);

  NetworkTransfer createPayoutTransfer(Money amount, String destinationBankKey);

  NetworkTransfer getPayoutTransfer(UUID transferPublicId);

  /** Withdraws a pending payout transfer. Returns the instruction's post-attempt
   * state: CANCELLED when the cancel won, the winner's terminal state when a
   * concurrent settlement won the status-guarded row, the observed state when
   * already terminal. */
  NetworkTransfer cancelPayoutTransfer(UUID transferPublicId);

  NetworkRefund createChargeRefund(UUID chargePublicId, Money amount);

  NetworkRefund getChargeRefund(UUID refundPublicId);

  /** Withdraws a pending charge refund; post-attempt semantics as
   * {@link #cancelPayoutTransfer(UUID)}. */
  NetworkRefund cancelChargeRefund(UUID refundPublicId);
}
