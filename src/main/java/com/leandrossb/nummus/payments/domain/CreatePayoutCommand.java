package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Duration;
import java.util.UUID;

/** Command to request a payout. A null ttl selects the default expiry window.
 *  The destination is a registered, verified bank account — the wire key is
 *  derived at request time. */
public record CreatePayoutCommand(UUID accountPublicId, Money amount,
    UUID bankAccountPublicId, Duration ttl) {
}
