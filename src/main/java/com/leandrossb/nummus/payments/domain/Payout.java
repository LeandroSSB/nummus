package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A merchant's payout (money-out). References the merchant's payment account and the
 * outbound network transfer by public UUID only; the journal links carry the two-phase
 * reservation — {@code requestTransactionPublicId} posts the reserve that holds the
 * funds aside, {@code executeTransactionPublicId} settles them out on success, and
 * {@code returnTransactionPublicId} releases the reserve back to the account on
 * expiry or failure. {@code feeAmount} is the settle-time fee fact — null until
 * settled, then the charged fee (a settled zero-fee payout carries {@code 0.00},
 * not null). {@code bankAccountPublicId} is the registered account this
 * payout paid into; null on pre-M20 rows.
 */
public record Payout(
    UUID publicId,
    UUID accountPublicId,
    Money amount,
    PayoutStatus status,
    String destinationBankKey,
    UUID bankAccountPublicId,
    UUID transferPublicId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt,
    Money feeAmount,
    UUID requestTransactionPublicId,
    UUID executeTransactionPublicId,
    UUID returnTransactionPublicId) {
}
