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
 * single-process fixedDelay discipline as the webhook workers. A tick held
 * back by a future-dated report warns once per stall episode instead of
 * failing, since retrying cannot help until the wall clock catches up.
 */
@Component
public class ConciliationWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConciliationWorker.class);

  private final ConciliationService conciliation;
  private final ConciliationStore store;
  private final ConciliationProperties properties;

  /** The stalled-episode latch: warn once, re-arm when the stall clears. */
  private volatile boolean stallWarned;

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
      // Held back: either the marker is ahead of the lagged now on its own, or
      // a report's period_to is. In the latter case divergence alerting is
      // paused until the wall clock passes the row (or it is corrected) — warn
      // once for the episode instead of spamming every poll.
      store.latestReportEnd().ifPresentOrElse(latest -> {
        if (latest.isAfter(end)) {
          if (!stallWarned) {
            LOGGER.warn("conciliation ticks are stalled: report period_to {} is ahead of the "
                + "lagged now; automated divergence alerting is paused until wall clock passes it "
                + "or the row is corrected", latest);
            stallWarned = true;
          }
        } else {
          stallWarned = false;
        }
      }, () -> stallWarned = false);
      return;
    }
    stallWarned = false;
    conciliation.ingestIfAnyLines(start, end);
    store.advanceWindowEnd(end);
  }
}
