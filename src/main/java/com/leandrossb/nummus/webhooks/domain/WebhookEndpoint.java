package com.leandrossb.nummus.webhooks.domain;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A merchant-registered delivery target. {@code eventTypes} empty means all types. */
public record WebhookEndpoint(
    UUID publicId, URI url, String secret, List<String> eventTypes,
    EndpointStatus status, Instant createdAt) {
}
