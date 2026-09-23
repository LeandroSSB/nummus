package com.leandrossb.nummus.payments.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.FeeQuotes;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.interfaces.dto.CreateIntentRequest;
import com.leandrossb.nummus.payments.interfaces.dto.IntentResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payment-intents")
class PaymentsController {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final PaymentsService payments;
  private final FeeQuotes quotes;
  private final RefundsService refunds;

  PaymentsController(PaymentsService payments, FeeQuotes quotes, RefundsService refunds) {
    this.payments = payments;
    this.quotes = quotes;
    this.refunds = refunds;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<IntentResponse> create(AuthenticatedMerchant merchant,
      @Valid @RequestBody CreateIntentRequest request) {
    var intent = payments.create(merchant.merchantPublicId(), new CreateIntentCommand(
        request.accountId(),
        Money.of(request.amount(), BRL),
        request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds())));
    return ResponseEntity
        .created(URI.create("/v1/payment-intents/" + intent.publicId()))
        .body(toResponse(merchant, intent));
  }

  @GetMapping("/{id}")
  IntentResponse get(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    var intent = payments.get(merchant.merchantPublicId(), id);
    return toResponse(merchant, intent);
  }

  /** One shape for both reads: the fee/net quote beside the refunded total —
   *  settled and open intents alike expose the sum (zero until refunds exist). */
  private IntentResponse toResponse(AuthenticatedMerchant merchant, PaymentIntent intent) {
    return IntentResponse.from(intent, quotes.quoteFor(merchant.merchantPublicId(), intent),
        refunds.refundedTotal(intent.publicId()));
  }
}
