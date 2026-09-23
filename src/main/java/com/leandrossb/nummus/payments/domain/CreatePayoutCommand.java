package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Duration;
import java.util.UUID;

/** Command to request a payout. A null ttl selects the default expiry window. */
public record CreatePayoutCommand(UUID accountPublicId, Money amount, String destinationBankKey,
    Duration ttl) {
}
