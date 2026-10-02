package com.leandrossb.nummus.webhooks.infrastructure;

import com.leandrossb.nummus.payments.application.TransferLifecycleEvent;
import com.leandrossb.nummus.payments.application.TransferLifecycleEvents;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the serialized envelope and its fan-out delivery rows inside the
 * caller's transaction: the event commits with the transfer and its journal
 * entry or not at all ({@code MANDATORY} propagation fails fast on any caller
 * that opens no transaction). The envelope is serialized exactly once — every
 * delivery sends these stored bytes.
 */
@Component
public class OutboxTransferLifecycleEvents implements TransferLifecycleEvents {

  private final WebhookStore store;
  private final ObjectMapper objectMapper;

  public OutboxTransferLifecycleEvents(WebhookStore store, ObjectMapper objectMapper) {
    this.store = store;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(TransferLifecycleEvent event) {
    UUID eventId = UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(new Envelope(
        eventId, event.type(), Instant.now(), new Data(
            event.publicId(), event.fromAccountPublicId(), event.toAccountPublicId(),
            event.amount().amount().toPlainString(),
            event.amount().currency().getCurrencyCode(),
            event.journalTransactionPublicId(), event.createdAt())));
    store.insertEvent(eventId, event.merchantPublicId(), event.type(), payload, Instant.now());
  }

  record Envelope(UUID id, String type, Instant occurredAt, Data data) {
  }

  record Data(UUID publicId, UUID fromAccountId, UUID toAccountId, String amount, String currency,
      UUID journalTransactionId, Instant createdAt) {
  }
}
