package com.leandrossb.nummus.payments.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.csv.Csv;
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
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payment-intents")
class PaymentsController {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final PaymentsService payments;
  private final FeeQuotes quotes;
  private final RefundsService refunds;

  @Value("${nummus.export.max-rows:10000}")
  private int exportMaxRows;

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

  @Idempotent
  @PostMapping("/{id}/void")
  ResponseEntity<IntentResponse> voidIntent(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return ResponseEntity
        .ok(toResponse(merchant, payments.voidIntent(merchant.merchantPublicId(), id)));
  }

  @GetMapping
  ResponseEntity<?> list(AuthenticatedMerchant merchant,
      @RequestHeader(value = "Accept", required = false) String accept,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID account,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (Csv.wantsCsv(accept)) {
      return csvList(merchant, status, account);
    }
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned intent's public id) only when the probe found an extra row.
    var page = payments.list(merchant.merchantPublicId(), status, account, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(compose(merchant, page));
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(compose(merchant, page.subList(0, limit)));
  }

  /** An export is the whole current result, not a page: after/limit are
   *  ignored, the row bound is the export cap, and truncation says so. */
  private ResponseEntity<String> csvList(AuthenticatedMerchant merchant, String status,
      UUID account) {
    var page = payments.list(merchant.merchantPublicId(), status, account, null,
        exportMaxRows + 1);
    var truncated = page.size() > exportMaxRows;
    var composed = compose(merchant, truncated ? page.subList(0, exportMaxRows) : page);
    var rows = new java.util.ArrayList<List<String>>(composed.size());
    for (IntentResponse r : composed) {
      rows.add(List.of(String.valueOf(r.publicId()), String.valueOf(r.accountId()),
          r.amount().toPlainString(), r.currency(), r.status(), String.valueOf(r.chargeId()),
          String.valueOf(r.expiresAt()), String.valueOf(r.createdAt()),
          r.settledAt() == null ? "" : String.valueOf(r.settledAt()),
          r.fee() == null ? "" : r.fee().toPlainString(),
          r.netAmount() == null ? "" : r.netAmount().toPlainString(),
          r.refundedTotal() == null ? "" : r.refundedTotal().toPlainString()));
    }
    var body = Csv.render(List.of("publicId", "accountId", "amount", "currency", "status",
        "chargeId", "expiresAt", "createdAt", "settledAt", "fee", "netAmount",
        "refundedTotal"), rows) + (truncated ? "# truncated: true\r\n" : "");
    return exportResponse("payment-intents", merchant.merchantPublicId(), body);
  }

  private ResponseEntity<String> exportResponse(String resource, UUID merchantPublicId,
      String body) {
    return ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
        .header("Content-Disposition", "attachment; filename=\"" + resource + "-"
            + merchantPublicId.toString().substring(0, 8) + ".csv\"")
        .body(body);
  }

  /** One shape for both reads: the fee/net quote beside the refunded total —
   *  settled and open intents alike expose the sum (zero until refunds exist). */
  private IntentResponse toResponse(AuthenticatedMerchant merchant, PaymentIntent intent) {
    return IntentResponse.from(intent, quotes.quoteFor(merchant.merchantPublicId(), intent),
        refunds.refundedTotal(intent.publicId()));
  }

  /** The by-id shape without per-row lookups: one schedule sweep, one grouped
   *  refunded-totals query, then the same IntentResponse the single read uses. */
  private List<IntentResponse> compose(AuthenticatedMerchant merchant, List<PaymentIntent> page) {
    var quotesForPage = quotes.quotesFor(merchant.merchantPublicId(), page);
    var refunded = refunds.refundedTotals(
        page.stream().map(PaymentIntent::publicId).toList());
    return page.stream()
        .map(intent -> IntentResponse.from(intent, quotesForPage.get(intent.publicId()),
            refunded.get(intent.publicId())))
        .toList();
  }
}
