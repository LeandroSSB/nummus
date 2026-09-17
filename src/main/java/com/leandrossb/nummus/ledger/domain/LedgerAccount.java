package com.leandrossb.nummus.ledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** A ledger account in the module-owned chart of accounts. {@code closedAt} is null while open. */
public record LedgerAccount(
    UUID publicId,
    String name,
    AccountType type,
    Currency currency,
    AccountStatus status,
    Instant openedAt,
    Instant closedAt) {
}
