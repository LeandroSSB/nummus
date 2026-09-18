package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Duration;
import java.util.UUID;

/** Command to create a payment intent. A null ttl selects the default expiry window. */
public record CreateIntentCommand(UUID accountPublicId, Money amount, Duration ttl) {
}
