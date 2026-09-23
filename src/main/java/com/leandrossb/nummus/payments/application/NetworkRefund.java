package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * A charge refund at the external network. The amount and originating charge
 * are echoed so callers can detect mutation.
 */
public record NetworkRefund(UUID publicId, UUID chargePublicId, Money amount,
    ChargeStatus status) {
}
