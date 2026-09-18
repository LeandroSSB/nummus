package com.leandrossb.nummus.webhooks.domain;

import java.util.UUID;

/** Raised for unknown AND soft-deleted endpoints — both are gone to callers. */
public class UnknownWebhookEndpointException extends RuntimeException {

  public UnknownWebhookEndpointException(UUID publicId) {
    super("webhook endpoint not found: " + publicId);
  }
}
