package com.leandrossb.nummus.webhooks.domain;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A delivery target owned by a merchant. {@code eventTypes} empty means all types. */
public record WebhookEndpoint(
    UUID merchantPublicId,
    UUID publicId, URI url, String secret, List<String> eventTypes,
    EndpointStatus status, Instant createdAt) {
}
