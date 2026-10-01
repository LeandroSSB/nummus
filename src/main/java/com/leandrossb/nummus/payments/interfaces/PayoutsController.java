package com.leandrossb.nummus.payments.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.interfaces.dto.CreatePayoutRequest;
import com.leandrossb.nummus.payments.interfaces.dto.PayoutResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payouts")
class PayoutsController {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final PayoutsService payouts;

  PayoutsController(PayoutsService payouts) {
    this.payouts = payouts;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<PayoutResponse> create(AuthenticatedMerchant merchant,
      @Valid @RequestBody CreatePayoutRequest request) {
    var payout = payouts.create(merchant.merchantPublicId(), new CreatePayoutCommand(
        request.accountId(),
        Money.of(request.amount(), BRL),
        request.bankAccountId(),
        request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds())));
    return ResponseEntity
        .created(URI.create("/v1/payouts/" + payout.publicId()))
        .body(PayoutResponse.from(payout));
  }

  @GetMapping("/{id}")
  PayoutResponse get(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return PayoutResponse.from(payouts.get(merchant.merchantPublicId(), id));
  }

  @GetMapping
  ResponseEntity<List<PayoutResponse>> list(AuthenticatedMerchant merchant,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID account,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned payout's public id) only when the probe found an extra row.
    var page = payouts.list(merchant.merchantPublicId(), status, account, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(PayoutResponse::from).toList());
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(PayoutResponse::from).toList());
  }
}
