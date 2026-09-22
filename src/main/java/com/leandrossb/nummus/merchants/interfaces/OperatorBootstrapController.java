package com.leandrossb.nummus.merchants.interfaces;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.interfaces.dto.BootstrapRequest;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateKeyResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Token-first entry point: mints the first operator key from the configured
 *  deployment token, once. Reachable without any API key by design — without
 *  the exemption the key gate would lock the recovery path behind the very
 *  keys it mints. */
@RestController
class OperatorBootstrapController {

  private final OperatorKeysService operatorKeys;

  OperatorBootstrapController(OperatorKeysService operatorKeys) {
    this.operatorKeys = operatorKeys;
  }

  @PostMapping("/v1/operator/bootstrap")
  ResponseEntity<CreateKeyResponse> bootstrap(@Valid @RequestBody BootstrapRequest request) {
    var issued = operatorKeys.bootstrap(request.token(), request.label());
    return ResponseEntity
        .created(URI.create("/v1/operator/api-keys/" + issued.key().publicId()))
        .body(CreateKeyResponse.from(issued));
  }
}
