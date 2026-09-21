package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled tumbling-window re-ingest: start self-heals past manual ingests,
 * the end holds back the lag, an empty window persists nothing but still
 * advances the marker, and the whole tick is one transaction.
 * A failed tick warns and retries the same window next time — the same
 * single-process fixedDelay discipline as the webhook workers.
 */
@Component
public class ConciliationWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConciliationWorker.class);

  private final ConciliationService conciliation;
  private final ConciliationStore store;
  private final ConciliationProperties properties;

  public ConciliationWorker(ConciliationService conciliation, ConciliationStore store,
      ConciliationProperties properties) {
    this.conciliation = conciliation;
    this.store = store;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${nummus.conciliation.poll-delay-ms:300000}",
      initialDelayString = "${nummus.conciliation.initial-delay-ms:60000}")
  @Transactional
  public void tick() {
    try {
      runWindow();
    } catch (Exception e) {
      LOGGER.warn("conciliation ingest tick failed; the window will retry", e);
    }
  }

  void runWindow() {
    Instant start = store.selfHealingWindowStart();
    Instant end = store.currentWindowEnd(properties.windowLag());
    if (!start.isBefore(end)) {
      return;
    }
    conciliation.ingestIfAnyLines(start, end);
    store.advanceWindowEnd(end);
  }
}
