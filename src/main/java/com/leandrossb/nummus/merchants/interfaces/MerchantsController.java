package com.leandrossb.nummus.merchants.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantStore;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.UnknownMerchantException;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateMerchantRequest;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateMerchantResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.FeeHistoryResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.MerchantResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.UpdateFeeRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator surface: merchants are created here, never self-served. */
@RestController
@RequestMapping("/v1/merchants")
class MerchantsController {

  private final MerchantsService merchants;
  private final ApiKeysService keys;
  private final MerchantStore store;

  MerchantsController(MerchantsService merchants, ApiKeysService keys, MerchantStore store) {
    this.merchants = merchants;
    this.keys = keys;
    this.store = store;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<CreateMerchantResponse> create(AuthenticatedOperator operator,
      @Valid @RequestBody CreateMerchantRequest request) {
    var merchant = merchants.create(request.name(), request.feeSchedule());
    var firstKey = keys.create(merchant.publicId(), null);
    return ResponseEntity
        .created(URI.create("/v1/merchants/" + merchant.publicId()))
        .body(CreateMerchantResponse.from(merchant, firstKey,
            merchants.findFeeSchedule(merchant.publicId()).orElseThrow()));
  }

  @GetMapping("/{id}")
  MerchantResponse get(AuthenticatedOperator operator, @PathVariable UUID id) {
    var merchant = merchants.find(id).orElseThrow(() -> new UnknownMerchantException(id));
    return MerchantResponse.from(merchant, merchants.findFeeSchedule(id).orElseThrow());
  }

  @Idempotent
  @PutMapping("/{id}/fee")
  ResponseEntity<MerchantResponse> updateFee(AuthenticatedOperator operator, @PathVariable UUID id,
      @Valid @RequestBody UpdateFeeRequest request) {
    merchants.updateFeeSchedule(id, new FeeSchedule(request.rate(), request.fixedAmount()),
        operator.keyPublicId());
    var merchant = merchants.find(id).orElseThrow(() -> new UnknownMerchantException(id));
    return ResponseEntity.ok(MerchantResponse.from(merchant,
        merchants.findFeeSchedule(id).orElseThrow()));
  }

  @GetMapping("/{id}/fee-history")
  ResponseEntity<List<FeeHistoryResponse>> feeHistory(AuthenticatedOperator operator,
      @PathVariable UUID id, @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    merchants.find(id).orElseThrow(() -> new UnknownMerchantException(id));
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned entry's public id) only when the probe found an extra row.
    var page = store.listFeeHistory(id, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(FeeHistoryResponse::from).toList());
    }
    var cursor = page.get(limit - 1).entryId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(FeeHistoryResponse::from).toList());
  }
}
