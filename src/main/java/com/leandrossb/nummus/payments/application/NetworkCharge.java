package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** A charge at the external network. The amount is echoed so callers can detect mutation. */
public record NetworkCharge(UUID publicId, Money amount, ChargeStatus status) {
}
