package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * At-least-once delivery: claims due PENDING rows, POSTs the exact stored
 * payload bytes, and records success / bounded exponential backoff / permanent
 * failure. Single-process by design (fixedDelay never overlaps itself);
 * scale-out needs SKIP LOCKED claiming — see the backlog.
 */
@Component
public class WebhookDeliveryWorker {

  private final WebhookStore store;
  private final EventDeliveryClient client;
  private final WebhookProperties properties;

  public WebhookDeliveryWorker(WebhookStore store, EventDeliveryClient client,
      WebhookProperties properties) {
    this.store = store;
    this.client = client;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${nummus.webhooks.poll-delay-ms:1000}",
      initialDelayString = "${nummus.webhooks.initial-delay-ms:1000}")
  public void deliverDue() {
    for (var due : store.claimDueDeliveries(Instant.now(), properties.batchSize())) {
      if (due.endpointStatus() != EndpointStatus.ACTIVE) {
        // Unsubscribed mid-flight: stop trying, keep the history.
        store.recordDeliveryFailure(due.id(), null);
        continue;
      }
      var result = client.deliver(due.url(), due.secret(), due.eventType(), due.payload());
      if (result.delivered()) {
        store.recordDeliverySuccess(due.id(), result.httpStatus());
      } else if (due.attempts() + 1 >= properties.maxAttempts()) {
        store.recordDeliveryFailure(due.id(), result.httpStatus());
      } else {
        store.recordDeliveryRetry(due.id(), result.httpStatus(),
            Instant.now().plus(backoffAfter(due.attempts() + 1)));
      }
    }
  }

  private Duration backoffAfter(int attempt) {
    return properties.backoffBase().multipliedBy((long) Math.pow(2, attempt));
  }
}
