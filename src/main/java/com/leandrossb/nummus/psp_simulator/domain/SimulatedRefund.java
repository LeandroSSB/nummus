package com.leandrossb.nummus.psp_simulator.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import java.time.Instant;
import java.util.UUID;

/** A charge refund as held by the simulated network. */
public record SimulatedRefund(
    UUID publicId, UUID chargePublicId, Money amount, ChargeStatus status,
    Instant createdAt, Instant updatedAt) {
}
