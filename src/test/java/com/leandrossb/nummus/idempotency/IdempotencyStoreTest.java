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
      store.insert(null, key, fingerprint, expiresAt);
      store.insert(null, key, new byte[] {9}, expiresAt);
    });
    Optional<StoredRow> found = store.findByKey(null, key);
    assertTrue(found.isPresent());
    assertArrayEquals(fingerprint, found.get().requestFingerprint());
    assertEquals(expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        found.get().expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertTrue(found.get().response() == null);
  }

  @Test
  void theSameKeyLivesIndependentlyInEachNamespace() {
    String key = freshKey();
    UUID merchant = UUID.randomUUID();
    store.insert(merchant, key, new byte[] {1}, Instant.now().plusSeconds(900));
    // The same key text under the operator namespace is a separate slot.
    store.insert(null, key, new byte[] {2}, Instant.now().plusSeconds(900));
    // Uniqueness still holds within one namespace.
    assertThrows(DuplicateKeyException.class,
        () -> store.insert(merchant, key, new byte[] {3}, Instant.now().plusSeconds(900)));
    // Lookup is scoped: each namespace sees only its own row.
    assertArrayEquals(new byte[] {1}, store.findByKey(merchant, key).orElseThrow().requestFingerprint());
    assertArrayEquals(new byte[] {2}, store.findByKey(null, key).orElseThrow().requestFingerprint());
    assertTrue(store.findByKey(UUID.randomUUID(), key).isEmpty());
  }

  @Test
  void attachResponseStoresTheSerializedResponseOnce() {
    String key = freshKey();
    store.insert(null, key, new byte[] {1}, Instant.now().plusSeconds(900));
    assertTrue(store.attachResponse(null, key, new StoredResponse(201, "application/json", "/v1/payment-intents/x", "{\"a\":1}")));
    assertFalse(store.attachResponse(null, key, new StoredResponse(200, "application/json", null, "{\"a\":2}")));
    StoredRow row = store.findByKey(null, key).orElseThrow();
    assertEquals(201, row.response().status());
    assertEquals("/v1/payment-intents/x", row.response().location());
    assertEquals("{\"a\":1}", row.response().body());
  }

  @Test
  void attachAndReclaimTouchOnlyTheirOwnNamespace() {
    // Expiry is judged by the database clock (now()), which can lag the JVM by
    // seconds when Testcontainers runs against a remote daemon — keep the
    // expired margin well above that skew.
    String key = freshKey();
    UUID merchant = UUID.randomUUID();
    store.insert(merchant, key, new byte[] {1}, Instant.now().minusSeconds(60));
    store.insert(null, key, new byte[] {2}, Instant.now().minusSeconds(60));

    // attachResponse stays in the caller's namespace: the merchant slot takes
    // the response, the operator slot with the same key text keeps none.
    assertTrue(store.attachResponse(merchant, key, new StoredResponse(201, "application/json", null, "{\"m\":1}")));
    assertEquals(201, store.findByKey(merchant, key).orElseThrow().response().status());
    assertTrue(store.findByKey(null, key).orElseThrow().response() == null);
    // The untouched operator slot is still attachable — and vice versa.
    assertTrue(store.attachResponse(null, key, new StoredResponse(200, "application/json", null, "{\"o\":1}")));

    // reclaimExpired likewise: claiming the operator's expired slot leaves the
    // merchant's same-key slot untouched, response included.
    assertTrue(store.reclaimExpired(null, key, new byte[] {9}, Instant.now().plusSeconds(900)));
    StoredRow operatorRow = store.findByKey(null, key).orElseThrow();
    assertArrayEquals(new byte[] {9}, operatorRow.requestFingerprint());
    assertTrue(operatorRow.response() == null);
    StoredRow merchantRow = store.findByKey(merchant, key).orElseThrow();
    assertArrayEquals(new byte[] {1}, merchantRow.requestFingerprint());
    assertEquals(201, merchantRow.response().status());
  }

  @Test
  void reclaimExpiredClaimsOnlyExpiredSlotsAndClearsTheResponse() {
    String key = freshKey();
    // Expiry is judged by the database clock (now()), which can lag the JVM by
    // seconds when Testcontainers runs against a remote daemon — keep the
    // expired margin well above that skew.
    store.insert(null, key, new byte[] {1}, Instant.now().minusSeconds(60));
    store.attachResponse(null, key, new StoredResponse(201, "application/json", null, "{}"));
    assertTrue(store.reclaimExpired(null, key, new byte[] {2}, Instant.now().plusSeconds(900)));
    StoredRow reclaimed = store.findByKey(null, key).orElseThrow();
    assertArrayEquals(new byte[] {2}, reclaimed.requestFingerprint());
    assertTrue(reclaimed.response() == null);
    // An unexpired slot refuses the reclaim.
    String fresh = freshKey();
    store.insert(null, fresh, new byte[] {1}, Instant.now().plusSeconds(900));
    assertFalse(store.reclaimExpired(null, fresh, new byte[] {2}, Instant.now().plusSeconds(900)));
  }

  @Test
  void purgeExpiredDeletesOnlyRowsPastTheirExpiry() {
    String old = freshKey();
    String kept = freshKey();
    store.insert(null, old, new byte[] {1}, Instant.now().minusSeconds(1));
    store.insert(null, kept, new byte[] {1}, Instant.now().plusSeconds(900));
    int purged = store.purgeExpired(Instant.now());
    assertTrue(purged >= 1);
    assertTrue(store.findByKey(null, old).isEmpty());
    assertTrue(store.findByKey(null, kept).isPresent());
  }
}
