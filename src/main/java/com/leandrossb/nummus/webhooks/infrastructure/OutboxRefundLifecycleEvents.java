package com.leandrossb.nummus.webhooks.infrastructure;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leandrossb.nummus.payments.application.RefundLifecycleEvent;
import com.leandrossb.nummus.payments.application.RefundLifecycleEvents;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the serialized envelope and its fan-out delivery rows inside the
 * caller's transaction: the event commits with the state change or not at
 * all ({@code MANDATORY} propagation fails fast on any caller that opens no
 * transaction). The envelope is serialized exactly once — every delivery
 * sends these stored bytes. The data carries no fee facts: processing fees
 * are retained, there is nothing to report.
 */
@Component
public class OutboxRefundLifecycleEvents implements RefundLifecycleEvents {

  private final WebhookStore store;
  private final ObjectMapper objectMapper;

  public OutboxRefundLifecycleEvents(WebhookStore store, ObjectMapper objectMapper) {
    this.store = store;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(RefundLifecycleEvent event) {
    UUID eventId = UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(new Envelope(
        eventId, event.type(), Instant.now(), new Data(
            event.publicId(), event.intentPublicId(),
            MoneyStrings.toMoneyString(event.amount()),
            event.amount().currency().getCurrencyCode(),
            event.status(), event.networkRefundPublicId(),
            event.settledAt(), event.journalTransactionPublicId())));
    store.insertEvent(eventId, event.merchantPublicId(), event.type(), payload, Instant.now());
  }

  record Envelope(UUID id, String type, Instant occurredAt, Data data) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Data(UUID refundId, UUID intentId, String amount, String currency,
      String status, UUID networkRefundId, Instant settledAt, UUID journalTransactionId) {
  }
}
