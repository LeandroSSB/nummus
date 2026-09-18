package com.leandrossb.nummus.webhooks.interfaces;

import com.leandrossb.nummus.webhooks.application.WebhookEndpointsService;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.interfaces.dto.DeliveryResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
  java.util.List<DeliveryResponse> deliveries(@PathVariable UUID id,
      @RequestParam(required = false) String status) {
    endpoints.get(id); // 404 for unknown or deleted endpoints
    return store.listDeliveries(id, status, 50).stream().map(DeliveryResponse::from).toList();
  }
}
