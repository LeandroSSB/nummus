package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import java.net.URI;

/** A delivery the worker may attempt now: target endpoint state plus the exact stored payload. */
public record DueDelivery(
    long id, EndpointStatus endpointStatus, URI url, String secret,
    String eventType, String payload, int attempts) {
}
