package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** What the network says it settled: a SUCCEEDED instruction in a window —
 *  kind is CHARGE, PAYOUT_TRANSFER, or CHARGE_REFUND. */
public record NetworkSettlement(String kind, UUID subjectPublicId, Money amount, Instant settledAt) {
}
