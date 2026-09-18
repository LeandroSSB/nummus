package com.leandrossb.nummus.webhooks.interfaces.dto;

import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Endpoint view without the secret. */
public record EndpointResponse(
    UUID publicId, String url, List<String> eventTypes, String status, Instant createdAt) {

  public static EndpointResponse from(WebhookEndpoint endpoint) {
    return new EndpointResponse(endpoint.publicId(), endpoint.url().toString(),
        endpoint.eventTypes(), endpoint.status().name(), endpoint.createdAt());
  }
}
