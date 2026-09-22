package com.leandrossb.nummus.webhooks.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
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

/** Operator self-serve surface for the operator webhook namespace: operators
 *  are role-level, so any operator key manages the shared endpoint set. */
@RestController
@RequestMapping("/v1/operator/webhook-endpoints")
class OperatorWebhookEndpointsController {

  private final WebhookEndpointsService endpoints;

  OperatorWebhookEndpointsController(WebhookEndpointsService endpoints) {
    this.endpoints = endpoints;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<CreateEndpointResponse> create(AuthenticatedOperator operator,
      @Valid @RequestBody CreateEndpointRequest request) {
    var endpoint = endpoints.registerOperator(URI.create(request.url()), request.eventTypes(),
        operator.keyPublicId());
    return ResponseEntity
        .created(URI.create("/v1/operator/webhook-endpoints/" + endpoint.publicId()))
        .body(CreateEndpointResponse.from(endpoint));
  }

  @GetMapping
  java.util.List<EndpointResponse> list(AuthenticatedOperator operator) {
    return endpoints.list(null).stream().map(EndpointResponse::from).toList();
  }

  @GetMapping("/{id}")
  EndpointResponse get(AuthenticatedOperator operator, @PathVariable UUID id) {
    return EndpointResponse.from(endpoints.get(null, id));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(AuthenticatedOperator operator, @PathVariable UUID id) {
    endpoints.deleteOperator(id, operator.keyPublicId());
    return ResponseEntity.noContent().build();
  }
}
