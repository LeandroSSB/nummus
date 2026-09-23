package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Duration;

/** Command to request a refund of a settled intent. A null ttl selects the default expiry window. */
public record CreateRefundCommand(Money amount, Duration ttl) {
}
