package com.leandrossb.nummus.webhooks.infrastructure;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leandrossb.nummus.payments.application.IntentLifecycleEvent;
import com.leandrossb.nummus.payments.application.IntentLifecycleEvents;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the serialized envelope and its fan-out delivery rows inside the
 * caller's transaction: the event commits with the state change or not at
 * all. The envelope is serialized exactly once — every delivery sends these
 * stored bytes.
 */
@Component
public class OutboxIntentLifecycleEvents implements IntentLifecycleEvents {

  private final WebhookStore store;
  private final ObjectMapper objectMapper;

  public OutboxIntentLifecycleEvents(WebhookStore store, ObjectMapper objectMapper) {
    this.store = store;
    this.objectMapper = objectMapper;
  }

  @Override
  public void publish(IntentLifecycleEvent event) {
    UUID eventId = UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(new Envelope(
        eventId, event.type(), Instant.now(), new Data(
            event.publicId(), event.accountPublicId(),
            event.amount().amount().toPlainString(),
            event.amount().currency().getCurrencyCode(),
            event.status(), event.chargePublicId(),
            event.settledAt(), event.journalTransactionPublicId())));
    store.insertEvent(eventId, event.type(), payload, Instant.now());
  }

  record Envelope(UUID id, String type, Instant occurredAt, Data data) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Data(UUID publicId, UUID accountId, String amount, String currency,
      String status, UUID chargeId, Instant settledAt, UUID journalTransactionId) {
  }
}
