package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A merchant's instant-payment intent. References the merchant's payment account and the
 * network charge by public UUID only; {@code journalTransactionPublicId} links the exactly-once
 * settlement entry once posted.
 */
public record PaymentIntent(
    UUID publicId,
    UUID accountPublicId,
    Money amount,
    IntentStatus status,
    UUID chargePublicId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt,
    UUID journalTransactionPublicId) {
}
