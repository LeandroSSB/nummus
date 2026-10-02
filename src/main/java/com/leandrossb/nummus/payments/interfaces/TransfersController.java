package com.leandrossb.nummus.payments.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.TransfersService;
import com.leandrossb.nummus.payments.domain.CreateTransferCommand;
import com.leandrossb.nummus.payments.interfaces.dto.CreateTransferRequest;
import com.leandrossb.nummus.payments.interfaces.dto.TransferResponse;
import jakarta.validation.Valid;
import java.net.URI;
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

/**
 * The internal-transfer REST surface: a create that moves booked funds between
 * two of the merchant's own accounts in one balanced entry, a read, and the
 * merchant's listing — the payout shape minus the lifecycle, for the create IS
 * the completed fact and no GET ever drives anything.
 */
@RestController
@RequestMapping("/v1/transfers")
class TransfersController {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final TransfersService transfers;

  TransfersController(TransfersService transfers) {
    this.transfers = transfers;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<TransferResponse> create(AuthenticatedMerchant merchant,
      @Valid @RequestBody CreateTransferRequest request) {
    var transfer = transfers.create(merchant.merchantPublicId(), new CreateTransferCommand(
        request.fromAccountId(), request.toAccountId(), Money.of(request.amount(), BRL)));
    return ResponseEntity
        .created(URI.create("/v1/transfers/" + transfer.publicId()))
        .body(TransferResponse.from(transfer));
  }

  @GetMapping("/{id}")
  TransferResponse get(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return TransferResponse.from(transfers.get(merchant.merchantPublicId(), id));
  }

  @GetMapping
  ResponseEntity<List<TransferResponse>> list(AuthenticatedMerchant merchant,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned transfer's public id) only when the probe found an extra row.
    var page = transfers.list(merchant.merchantPublicId(), after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(TransferResponse::from).toList());
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(TransferResponse::from).toList());
  }
}
