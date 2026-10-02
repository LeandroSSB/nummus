package com.leandrossb.nummus.payments.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.csv.Csv;
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
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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

  @Value("${nummus.export.max-rows:10000}")
  private int exportMaxRows;

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

  @GetMapping("/v1/refunds")
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
    // (the last returned refund's public id) only when the probe found an extra row.
    var page = refunds.list(merchant.merchantPublicId(), status, account, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(RefundResponse::from).toList());
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(RefundResponse::from).toList());
  }

  /** An export is the whole current result, not a page: after/limit are
   *  ignored, the row bound is the export cap, and truncation says so. */
  private ResponseEntity<String> csvList(AuthenticatedMerchant merchant, String status,
      UUID account) {
    var page = refunds.list(merchant.merchantPublicId(), status, account, null,
        exportMaxRows + 1);
    var truncated = page.size() > exportMaxRows;
    var composed = (truncated ? page.subList(0, exportMaxRows) : page)
        .stream().map(RefundResponse::from).toList();
    var rows = new java.util.ArrayList<List<String>>(composed.size());
    for (RefundResponse r : composed) {
      rows.add(List.of(
          r.publicId() == null ? "" : String.valueOf(r.publicId()),
          r.intentId() == null ? "" : String.valueOf(r.intentId()),
          r.amount() == null ? "" : r.amount().toPlainString(),
          r.currency() == null ? "" : r.currency(),
          r.status() == null ? "" : r.status(),
          r.networkRefundId() == null ? "" : String.valueOf(r.networkRefundId()),
          r.expiresAt() == null ? "" : String.valueOf(r.expiresAt()),
          r.createdAt() == null ? "" : String.valueOf(r.createdAt()),
          r.settledAt() == null ? "" : String.valueOf(r.settledAt()),
          r.holdTransactionId() == null ? "" : String.valueOf(r.holdTransactionId())));
    }
    var body = Csv.render(List.of("publicId", "intentId", "amount", "currency", "status",
        "networkRefundId", "expiresAt", "createdAt", "settledAt", "holdTransactionId"), rows)
        + (truncated ? "# truncated: true\r\n" : "");
    return exportResponse("refunds", merchant.merchantPublicId(), body);
  }

  private ResponseEntity<String> exportResponse(String resource, UUID merchantPublicId,
      String body) {
    return ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
        .header("Content-Disposition", "attachment; filename=\"" + resource + "-"
            + merchantPublicId.toString().substring(0, 8) + ".csv\"")
        .body(body);
  }
}
