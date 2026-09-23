package com.leandrossb.nummus.payments.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.interfaces.dto.CreateRefundRequest;
import com.leandrossb.nummus.payments.interfaces.dto.RefundResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The refund REST surface: a create nested under its intent, a read under the
 * refund's own prefix. One class with method-level mappings — the two paths
 * share nothing but the service. The GET is the lazy lifecycle's only driver:
 * reading a refund settles or returns its hold when the network side moved.
 */
@RestController
class RefundsController {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final RefundsService refunds;

  RefundsController(RefundsService refunds) {
    this.refunds = refunds;
  }

  @Idempotent
  @PostMapping("/v1/payment-intents/{intentId}/refunds")
  ResponseEntity<RefundResponse> create(AuthenticatedMerchant merchant,
      @PathVariable UUID intentId, @Valid @RequestBody CreateRefundRequest request) {
    var refund = refunds.create(merchant.merchantPublicId(), intentId, new CreateRefundCommand(
        Money.of(request.amount(), BRL),
        request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds())));
    return ResponseEntity
        .created(URI.create("/v1/refunds/" + refund.publicId()))
        .body(RefundResponse.from(refund));
  }

  @GetMapping("/v1/refunds/{id}")
  RefundResponse get(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return RefundResponse.from(refunds.get(merchant.merchantPublicId(), id));
  }
}
