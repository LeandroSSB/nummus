package com.leandrossb.nummus.merchants.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A merchant's registered payout destination — the structured Brazilian bank
 * account. {@code status} is PENDING_VERIFICATION until the one-time code is
 * redeemed, then VERIFIED; REVOKED is the soft-delete terminal. The wire key
 * payouts derive ({@code bank-branch-account}) is never stored as identity —
 * the structured fields are the truth.
 */
public record BankAccount(UUID publicId, UUID merchantPublicId, String bankCode, String branch,
    String accountNumber, String holderTaxId, String status, Instant createdAt,
    Instant verifiedAt) {
}
