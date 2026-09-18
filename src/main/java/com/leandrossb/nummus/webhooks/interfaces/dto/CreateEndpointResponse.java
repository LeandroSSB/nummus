package com.leandrossb.nummus.webhooks.interfaces.dto;

import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Create response — the ONLY surface that ever carries the signing secret. */
public record CreateEndpointResponse(
    UUID publicId, String url, List<String> eventTypes, String status,
    Instant createdAt, String secret) {

  public static CreateEndpointResponse from(WebhookEndpoint endpoint) {
    return new CreateEndpointResponse(endpoint.publicId(), endpoint.url().toString(),
        endpoint.eventTypes(), endpoint.status().name(), endpoint.createdAt(), endpoint.secret());
  }
}
