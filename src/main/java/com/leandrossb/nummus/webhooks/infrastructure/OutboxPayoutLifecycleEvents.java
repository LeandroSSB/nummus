package com.leandrossb.nummus.webhooks.infrastructure;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PayoutLifecycleEvent;
import com.leandrossb.nummus.payments.application.PayoutLifecycleEvents;
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
 * sends these stored bytes.
 */
@Component
public class OutboxPayoutLifecycleEvents implements PayoutLifecycleEvents {

  private final WebhookStore store;
  private final ObjectMapper objectMapper;

  public OutboxPayoutLifecycleEvents(WebhookStore store, ObjectMapper objectMapper) {
    this.store = store;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(PayoutLifecycleEvent event) {
    UUID eventId = UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(new Envelope(
        eventId, event.type(), Instant.now(), new Data(
            event.publicId(), event.accountPublicId(),
            event.amount().amount().toPlainString(),
            event.amount().currency().getCurrencyCode(),
            event.status(), event.transferPublicId(), event.destinationBankKey(),
            event.settledAt(), event.journalTransactionPublicId(),
            toMoneyString(event.fee()), toMoneyString(event.netAmount()))));
    store.insertEvent(eventId, event.merchantPublicId(), event.type(), payload, Instant.now());
  }

  /**
   * Execution fee facts as merchant-facing strings: at least centavos, never
   * spurious trailing zeros (a journal-scale {@code 98.6200} publishes as
   * {@code 98.62}). Lossless — only exact trailing zeros are dropped.
   */
  private static String toMoneyString(Money money) {
    if (money == null) {
      return null;
    }
    var stripped = money.amount().stripTrailingZeros();
    return stripped.setScale(Math.max(2, stripped.scale())).toPlainString();
  }

  record Envelope(UUID id, String type, Instant occurredAt, Data data) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Data(UUID publicId, UUID accountId, String amount, String currency,
      String status, UUID transferId, String destinationBankKey, Instant settledAt,
      UUID journalTransactionId, String fee, String netAmount) {
  }
}
