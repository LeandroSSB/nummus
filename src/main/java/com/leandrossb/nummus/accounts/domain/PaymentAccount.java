package com.leandrossb.nummus.accounts.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A payment account owned by a merchant and held on behalf of a holder.
 * Wraps exactly one backing ledger account (type LIABILITY), referenced by
 * public UUID — the accounts module never sees ledger internals.
 */
public record PaymentAccount(
    UUID merchantPublicId,
    UUID publicId,
    String holderName,
    AccountStatus status,
    Instant openedAt,
    Instant closedAt,
    UUID ledgerAccountPublicId) {
}
