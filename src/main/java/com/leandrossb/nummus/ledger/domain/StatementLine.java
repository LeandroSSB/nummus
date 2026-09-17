package com.leandrossb.nummus.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** One posting in an account statement, with its originating transaction context. */
public record StatementLine(
    Instant bookedAt,
    UUID transactionPublicId,
    String memo,
    Direction direction,
    Money amount) {
}
