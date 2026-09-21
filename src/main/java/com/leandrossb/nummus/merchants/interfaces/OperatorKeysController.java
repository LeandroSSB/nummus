package com.leandrossb.nummus.merchants.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.interfaces.dto.ApiKeyResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateKeyRequest;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateKeyResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator self-serve surface: operators are role-level, so any operator key
 *  manages the single operator key set. */
@RestController
@RequestMapping("/v1/operator/api-keys")
class OperatorKeysController {

  private final OperatorKeysService operatorKeys;

  OperatorKeysController(OperatorKeysService operatorKeys) {
    this.operatorKeys = operatorKeys;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<CreateKeyResponse> createKey(AuthenticatedOperator operator,
      @RequestBody(required = false) CreateKeyRequest request) {
    var issued = operatorKeys.create(request == null ? null : request.expiresIn());
    return ResponseEntity
        .created(URI.create("/v1/operator/api-keys/" + issued.key().publicId()))
        .body(CreateKeyResponse.from(issued));
  }

  @GetMapping
  List<ApiKeyResponse> listKeys(AuthenticatedOperator operator) {
    return operatorKeys.list().stream().map(ApiKeyResponse::from).toList();
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> revoke(AuthenticatedOperator operator, @PathVariable UUID id) {
    operatorKeys.revoke(id);
    return ResponseEntity.noContent().build();
  }
}
