package com.leandrossb.nummus.webhooks.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.webhooks.application.WebhookEndpointsService;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookDeliveryException;
import com.leandrossb.nummus.webhooks.interfaces.dto.DeliveryResponse;
import java.util.List;
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
  ResponseEntity<List<DeliveryResponse>> deliveries(AuthenticatedMerchant merchant,
      @PathVariable UUID id, @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    endpoints.get(merchant.merchantPublicId(), id); // 404 for unknown, deleted, or foreign endpoints
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned delivery's public id) only when the probe found an extra row.
    var page = store.listDeliveries(merchant.merchantPublicId(), id, status, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(DeliveryResponse::from).toList());
    }
    var cursor = page.get(limit - 1).deliveryPublicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(DeliveryResponse::from).toList());
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
