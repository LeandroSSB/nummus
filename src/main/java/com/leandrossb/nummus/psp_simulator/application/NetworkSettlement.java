package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** What the network says it settled: a SUCCEEDED charge in a window. */
public record NetworkSettlement(UUID chargePublicId, Money amount, Instant settledAt) {
}
