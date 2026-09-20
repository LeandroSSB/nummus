package com.leandrossb.nummus.webhooks.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.webhooks.application.WebhookEndpointsService;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookDeliveryException;
import com.leandrossb.nummus.webhooks.interfaces.dto.DeliveryResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WebhookDeliveriesController {

  private final WebhookEndpointsService endpoints;
  private final WebhookStore store;

  WebhookDeliveriesController(WebhookEndpointsService endpoints, WebhookStore store) {
    this.endpoints = endpoints;
    this.store = store;
  }

  @GetMapping("/v1/webhook-endpoints/{id}/deliveries")
  java.util.List<DeliveryResponse> deliveries(AuthenticatedMerchant merchant, @PathVariable UUID id,
      @RequestParam(required = false) String status) {
    endpoints.get(merchant.merchantPublicId(), id); // 404 for unknown, deleted, or foreign endpoints
    return store.listDeliveries(merchant.merchantPublicId(), id, status, 50).stream()
        .map(DeliveryResponse::from).toList();
  }

  @Idempotent
  @PostMapping("/v1/webhook-deliveries/{id}/redrive")
  ResponseEntity<Void> redrive(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    if (!store.requeueFailedDelivery(merchant.merchantPublicId(), id)) {
      throw new UnknownWebhookDeliveryException(id);
    }
    return ResponseEntity.accepted().build();
  }
}
