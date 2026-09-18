package com.leandrossb.nummus.payments.interfaces.dto;

import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** REST view of a payment intent. UUIDs only; settledAt/journal link appear post-settlement. */
public record IntentResponse(
    UUID publicId,
    UUID accountId,
    BigDecimal amount,
    String currency,
    String status,
    UUID chargeId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt) {

  public static IntentResponse from(PaymentIntent intent) {
    return new IntentResponse(intent.publicId(), intent.accountPublicId(),
        intent.amount().amount(), intent.amount().currency().getCurrencyCode(),
        intent.status().name(), intent.chargePublicId(), intent.expiresAt(),
        intent.createdAt(), intent.settledAt());
  }
}
