package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.interfaces.idempotency.IdempotencyStore;
import com.leandrossb.nummus.interfaces.idempotency.StoredResponse;
import com.leandrossb.nummus.interfaces.idempotency.StoredRow;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyStoreTest extends IntegrationTestBase {

  @Autowired
  private IdempotencyStore store;

  private String freshKey() {
    return "store-" + UUID.randomUUID();
  }

  @Test
  void insertThenFindByKeyRoundTripsWithoutAResponse() {
    String key = freshKey();
    byte[] fingerprint = {1, 2, 3};
    // timestamptz keeps microseconds (rounded to nearest), so write an
    // already microsecond-precision instant to make the round-trip exact.
    Instant expiresAt = Instant.now().plusSeconds(900)
        .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    assertThrows(DuplicateKeyException.class, () -> {
      store.insert(key, fingerprint, expiresAt);
      store.insert(key, new byte[] {9}, expiresAt);
    });
    Optional<StoredRow> found = store.findByKey(key);
    assertTrue(found.isPresent());
    assertArrayEquals(fingerprint, found.get().requestFingerprint());
    assertEquals(expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        found.get().expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertTrue(found.get().response() == null);
  }

  @Test
  void attachResponseStoresTheSerializedResponseOnce() {
    String key = freshKey();
    store.insert(key, new byte[] {1}, Instant.now().plusSeconds(900));
    assertTrue(store.attachResponse(key, new StoredResponse(201, "application/json", "/v1/payment-intents/x", "{\"a\":1}")));
    assertFalse(store.attachResponse(key, new StoredResponse(200, "application/json", null, "{\"a\":2}")));
    StoredRow row = store.findByKey(key).orElseThrow();
    assertEquals(201, row.response().status());
    assertEquals("/v1/payment-intents/x", row.response().location());
    assertEquals("{\"a\":1}", row.response().body());
  }

  @Test
  void reclaimExpiredClaimsOnlyExpiredSlotsAndClearsTheResponse() {
    String key = freshKey();
    // Expiry is judged by the database clock (now()), which can lag the JVM by
    // seconds when Testcontainers runs against a remote daemon — keep the
    // expired margin well above that skew.
    store.insert(key, new byte[] {1}, Instant.now().minusSeconds(60));
    store.attachResponse(key, new StoredResponse(201, "application/json", null, "{}"));
    assertTrue(store.reclaimExpired(key, new byte[] {2}, Instant.now().plusSeconds(900)));
    StoredRow reclaimed = store.findByKey(key).orElseThrow();
    assertArrayEquals(new byte[] {2}, reclaimed.requestFingerprint());
    assertTrue(reclaimed.response() == null);
    // An unexpired slot refuses the reclaim.
    String fresh = freshKey();
    store.insert(fresh, new byte[] {1}, Instant.now().plusSeconds(900));
    assertFalse(store.reclaimExpired(fresh, new byte[] {2}, Instant.now().plusSeconds(900)));
  }

  @Test
  void purgeExpiredDeletesOnlyRowsPastTheirExpiry() {
    String old = freshKey();
    String kept = freshKey();
    store.insert(old, new byte[] {1}, Instant.now().minusSeconds(1));
    store.insert(kept, new byte[] {1}, Instant.now().plusSeconds(900));
    int purged = store.purgeExpired(Instant.now());
    assertTrue(purged >= 1);
    assertTrue(store.findByKey(old).isEmpty());
    assertTrue(store.findByKey(kept).isPresent());
  }
}
