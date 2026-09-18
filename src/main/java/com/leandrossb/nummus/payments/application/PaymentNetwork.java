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
}
