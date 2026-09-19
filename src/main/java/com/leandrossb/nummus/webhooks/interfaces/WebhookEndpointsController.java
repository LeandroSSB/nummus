package com.leandrossb.nummus.webhooks.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.webhooks.application.WebhookEndpointsService;
import com.leandrossb.nummus.webhooks.interfaces.dto.CreateEndpointRequest;
import com.leandrossb.nummus.webhooks.interfaces.dto.CreateEndpointResponse;
import com.leandrossb.nummus.webhooks.interfaces.dto.EndpointResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/webhook-endpoints")
class WebhookEndpointsController {

  private final WebhookEndpointsService endpoints;

  WebhookEndpointsController(WebhookEndpointsService endpoints) {
    this.endpoints = endpoints;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<CreateEndpointResponse> create(AuthenticatedMerchant merchant,
      @Valid @RequestBody CreateEndpointRequest request) {
    var endpoint = endpoints.register(merchant.merchantPublicId(), URI.create(request.url()), request.eventTypes());
    return ResponseEntity
        .created(URI.create("/v1/webhook-endpoints/" + endpoint.publicId()))
        .body(CreateEndpointResponse.from(endpoint));
  }

  @GetMapping
  java.util.List<EndpointResponse> list(AuthenticatedMerchant merchant) {
    return endpoints.list(merchant.merchantPublicId()).stream().map(EndpointResponse::from).toList();
  }

  @GetMapping("/{id}")
  EndpointResponse get(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return EndpointResponse.from(endpoints.get(merchant.merchantPublicId(), id));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    endpoints.delete(merchant.merchantPublicId(), id);
    return ResponseEntity.noContent().build();
  }
}
