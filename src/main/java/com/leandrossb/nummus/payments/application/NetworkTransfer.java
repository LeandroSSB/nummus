package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * An outbound transfer at the external network. The amount and destination are
 * echoed so callers can detect mutation.
 */
public record NetworkTransfer(UUID publicId, Money amount, String destinationBankKey,
    ChargeStatus status) {
}
