package com.leandrossb.nummus.webhooks.application;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prunes succeeded deliveries past the retention TTL. FAILED and PENDING
 * rows are never pruned — audit history and redrive targets survive. Batches
 * like the delivery worker; a racing second instance would only issue
 * idempotent deletes (same single-process stance).
 */
@Component
public class WebhookRetentionWorker {

  private final WebhookStore store;
  private final WebhookProperties properties;

  public WebhookRetentionWorker(WebhookStore store, WebhookProperties properties) {
    this.store = store;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${nummus.webhooks.retention-delay-ms:3600000}")
  public void prune() {
    if (properties.retentionDays() <= 0) {
      return;
    }
    store.pruneSucceededBefore(Instant.now().minus(
        java.time.Duration.ofDays(properties.retentionDays())), properties.batchSize());
  }
}
