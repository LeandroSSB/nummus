package com.leandrossb.nummus.payments.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.csv.Csv;
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
@RequestMapping("/v1/payouts")
class PayoutsController {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final PayoutsService payouts;

  @Value("${nummus.export.max-rows:10000}")
  private int exportMaxRows;

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
    // (the last returned payout's public id) only when the probe found an extra row.
    var page = payouts.list(merchant.merchantPublicId(), status, account, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(PayoutResponse::from).toList());
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(PayoutResponse::from).toList());
  }

  /** An export is the whole current result, not a page: after/limit are
   *  ignored, the row bound is the export cap, and truncation says so. */
  private ResponseEntity<String> csvList(AuthenticatedMerchant merchant, String status,
      UUID account) {
    var page = payouts.list(merchant.merchantPublicId(), status, account, null,
        exportMaxRows + 1);
    var truncated = page.size() > exportMaxRows;
    var composed = (truncated ? page.subList(0, exportMaxRows) : page)
        .stream().map(PayoutResponse::from).toList();
    var rows = new java.util.ArrayList<List<String>>(composed.size());
    for (PayoutResponse r : composed) {
      rows.add(List.of(
          r.publicId() == null ? "" : String.valueOf(r.publicId()),
          r.accountId() == null ? "" : String.valueOf(r.accountId()),
          r.amount() == null ? "" : r.amount().toPlainString(),
          r.currency() == null ? "" : r.currency(),
          r.destinationBankKey() == null ? "" : r.destinationBankKey(),
          r.bankAccountId() == null ? "" : String.valueOf(r.bankAccountId()),
          r.status() == null ? "" : r.status(),
          r.transferId() == null ? "" : String.valueOf(r.transferId()),
          r.expiresAt() == null ? "" : String.valueOf(r.expiresAt()),
          r.createdAt() == null ? "" : String.valueOf(r.createdAt()),
          r.settledAt() == null ? "" : String.valueOf(r.settledAt()),
          r.fee() == null ? "" : r.fee().toPlainString(),
          r.reservationTransactionId() == null ? "" : String.valueOf(
              r.reservationTransactionId())));
    }
    var body = Csv.render(List.of("publicId", "accountId", "amount", "currency",
        "destinationBankKey", "bankAccountId", "status", "transferId", "expiresAt", "createdAt",
        "settledAt", "fee", "reservationTransactionId"), rows)
        + (truncated ? "# truncated: true\r\n" : "");
    return exportResponse("payouts", merchant.merchantPublicId(), body);
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
