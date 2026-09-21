package com.leandrossb.nummus.merchants.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.UnknownMerchantException;
import com.leandrossb.nummus.merchants.interfaces.dto.ApiKeyResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateKeyRequest;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateKeyResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.MerchantResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.RotateKeyRequest;
import com.leandrossb.nummus.merchants.interfaces.dto.RotateKeyResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Self-serve surface: the authenticated merchant manages its own keys. */
@RestController
class MeController {

  private final ApiKeysService keys;
  private final MerchantsService merchants;

  MeController(ApiKeysService keys, MerchantsService merchants) {
    this.keys = keys;
    this.merchants = merchants;
  }

  @GetMapping("/v1/me")
  MerchantResponse me(AuthenticatedMerchant merchant) {
    var self = merchants.find(merchant.merchantPublicId())
        .orElseThrow(() -> new UnknownMerchantException(merchant.merchantPublicId()));
    return MerchantResponse.from(self,
        merchants.findFeeSchedule(merchant.merchantPublicId()).orElseThrow());
  }

  @Idempotent
  @PostMapping("/v1/me/api-keys")
  ResponseEntity<CreateKeyResponse> createKey(AuthenticatedMerchant merchant,
      @RequestBody(required = false) CreateKeyRequest request) {
    var issued = keys.create(merchant.merchantPublicId(),
        request == null ? null : request.expiresIn());
    return ResponseEntity
        .created(URI.create("/v1/me/api-keys/" + issued.key().publicId()))
        .body(CreateKeyResponse.from(issued));
  }

  @GetMapping("/v1/me/api-keys")
  List<ApiKeyResponse> listKeys(AuthenticatedMerchant merchant) {
    return keys.list(merchant.merchantPublicId()).stream().map(ApiKeyResponse::from).toList();
  }

  @Idempotent
  @PostMapping("/v1/me/api-keys/current/rotate")
  ResponseEntity<RotateKeyResponse> rotateKey(AuthenticatedMerchant merchant,
      @RequestBody(required = false) RotateKeyRequest request) {
    var rotated = keys.rotate(merchant.merchantPublicId(), merchant.keyPublicId(),
        request == null ? null : request.expiresIn());
    return ResponseEntity
        .created(URI.create("/v1/me/api-keys/" + rotated.issued().key().publicId()))
        .body(RotateKeyResponse.from(rotated));
  }

  @DeleteMapping("/v1/me/api-keys/{id}")
  ResponseEntity<Void> revoke(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    keys.revoke(merchant.merchantPublicId(), id);
    return ResponseEntity.noContent().build();
  }
}
