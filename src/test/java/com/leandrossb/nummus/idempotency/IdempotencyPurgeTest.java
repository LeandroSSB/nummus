package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.interfaces.idempotency.IdempotencyPurgeJob;
import com.leandrossb.nummus.interfaces.idempotency.IdempotencyStore;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyPurgeTest extends IntegrationTestBase {

  @Autowired
  private IdempotencyStore store;

  @Autowired
  private IdempotencyPurgeJob job;

  @Test
  void purgeRemovesOnlyExpiredRows() {
    String expired = "purge-" + UUID.randomUUID();
    String alive = "purge-" + UUID.randomUUID();
    store.insert(null, expired, new byte[] {1}, Instant.now().minusSeconds(1));
    store.insert(null, alive, new byte[] {1}, Instant.now().plusSeconds(3600));

    job.purge();

    assertTrue(store.findByKey(null, expired).isEmpty());
    assertFalse(store.findByKey(null, alive).isEmpty());
  }
}
