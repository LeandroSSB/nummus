package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** One line of the network's settlement report, in conciliation's vocabulary. */
public record NetworkSettlement(UUID chargePublicId, Money amount, Instant settledAt) {
}
