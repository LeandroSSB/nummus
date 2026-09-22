package com.leandrossb.nummus.psp_simulator.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import java.time.Instant;
import java.util.UUID;

/** An outbound transfer as held by the simulated network. */
public record SimulatedTransfer(
    UUID publicId, Money amount, String destinationBankKey, ChargeStatus status,
    Instant createdAt, Instant updatedAt) {
}
