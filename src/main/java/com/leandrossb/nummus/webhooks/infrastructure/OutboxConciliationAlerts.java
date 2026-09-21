package com.leandrossb.nummus.webhooks.infrastructure;

import com.leandrossb.nummus.conciliation.application.ConciliationAlerts;
import com.leandrossb.nummus.conciliation.application.ConciliationEventTypes;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the report-open digest into the outbox inside the ingest
 * transaction ({@code MANDATORY} fails fast without one). Audience is NULL —
 * the operator namespace — so fan-out reaches operator endpoints only. The
 * envelope carries the divergence tally: counts and ids, never amounts.
 */
@Component
public class OutboxConciliationAlerts implements ConciliationAlerts {

  private final WebhookStore store;
  private final ObjectMapper objectMapper;

  public OutboxConciliationAlerts(WebhookStore store, ObjectMapper objectMapper) {
    this.store = store;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void reportOpen(UUID reportPublicId, Instant from, Instant to, int matched,
      int amountMismatched, int missingInternal, int missingExternal) {
    UUID eventId = UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(new Envelope(eventId,
        ConciliationEventTypes.REPORT_OPEN, Instant.now(),
        new Data(reportPublicId, from, to, matched, amountMismatched, missingInternal,
            missingExternal)));
    store.insertEvent(eventId, null, ConciliationEventTypes.REPORT_OPEN, payload, Instant.now());
  }

  record Envelope(UUID id, String type, Instant occurredAt, Data data) {
  }

  record Data(UUID reportId, Instant from, Instant to, int matched, int amountMismatched,
      int missingInternal, int missingExternal) {
  }
}
