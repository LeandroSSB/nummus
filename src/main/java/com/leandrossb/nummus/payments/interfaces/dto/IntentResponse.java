package com.leandrossb.nummus.payments.interfaces.dto;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.FeeQuote;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * REST view of a payment intent. UUIDs only; settledAt/journal link appear post-settlement.
 * Fee and net are the settled fact once settled, the current schedule's quote before that.
 * The refunded total is what a further refund request is capped against — zero until
 * refunds exist, and FAILED/EXPIRED refunds release their share of it.
 */
public record IntentResponse(
    UUID publicId,
    UUID accountId,
    BigDecimal amount,
    String currency,
    String status,
    UUID chargeId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt,
    BigDecimal fee,
    BigDecimal netAmount,
    BigDecimal refundedTotal) {

  public static IntentResponse from(PaymentIntent intent, FeeQuote quote, Money refundedTotal) {
    return new IntentResponse(intent.publicId(), intent.accountPublicId(),
        intent.amount().amount(), intent.amount().currency().getCurrencyCode(),
        intent.status().name(), intent.chargePublicId(), intent.expiresAt(),
        intent.createdAt(), intent.settledAt(), quote.fee().amount(), quote.netAmount().amount(),
        refundedTotal.amount());
  }
}
