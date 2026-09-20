package com.leandrossb.nummus.webhooks.domain;

import java.util.UUID;

/** Raised for unknown, foreign, and non-FAILED deliveries — all are gone to callers. */
public class UnknownWebhookDeliveryException extends RuntimeException {

  public UnknownWebhookDeliveryException(UUID publicId) {
    super("webhook delivery not found: " + publicId);
  }
}
