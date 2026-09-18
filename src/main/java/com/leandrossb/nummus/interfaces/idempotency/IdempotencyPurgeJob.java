package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly sweep of expired stored responses; reclaims already handle correctness, this bounds the table. */
@Component
public class IdempotencyPurgeJob {

  private final IdempotencyStore store;

  public IdempotencyPurgeJob(IdempotencyStore store) {
    this.store = store;
  }

  @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
  public void purge() {
    store.purgeExpired(Instant.now());
  }
}
